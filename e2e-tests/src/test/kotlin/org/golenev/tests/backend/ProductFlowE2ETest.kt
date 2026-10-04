package org.golenev.tests.backend

import org.golenev.db.tables.incomingGoodsDiagnostics.IncomingGoodsDiagnosticsDao
import org.golenev.db.tables.deliveryDiagnostics.DeliveryDiagnosticsDao
import org.golenev.db.tables.warehouseOutbox.WarehouseOutboxDao
import org.golenev.db.tables.stockMovements.StockMovementsDao
import org.golenev.utils.JsonUtils
import org.golenev.restapi.endpoints.*
import org.golenev.utils.*
import org.golenev.commondto.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.golenev.utils.required
import java.time.Instant
import java.util.UUID

/** Настоящая цепочка приёмки, расчёта и прихода. Подготовка не создаёт остаток напрямую и не подменяет проверяемый результат. */
@DisplayName("Поставки, оприходование и порядок цен")
class ProductFlowE2ETest {
    private val warehouseService = WarehouseServiceDao()

    /** Корректная поставка сохраняет данные товара и получает порядок, время и правило расчёта до прихода в STORE. */
    @Test @AllureId("1") @DisplayName("Поставка сохраняет данные товара и оприходуется по рассчитанной цене")
    fun realSupplyPreservesReceivingMetadata() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val expectedEvent = Shop.delivery(scope, quantity = 10, price = "100.00")
            val expectedLine = expectedEvent.payload.items.single()
            val stock = step("Отправляем поставку и ожидаем её оприходование в магазине") { Shop.supply(scope, expectedEvent) }
            step("Проверяем цену, количество и неизменяемые сведения первой приёмки") {
                assertEquals(expectedLine.productId, stock.productId)
                assertEquals(expectedLine.shortName, stock.shortName)
                assertEquals(expectedLine.description, stock.description)
                assertEquals("120.00", stock.unitPrice)
                assertEquals(10, stock.availableQuantity)
                val receiving = Shop.received(expectedEvent)
                assertEquals(scope.store, receiving.storeId)
                assertEquals(expectedEvent.payload.deliveryId, receiving.deliveryId)
                assertEquals(1L, receiving.deliverySequence)
                assertTrue(!Instant.parse(receiving.receivedAt).isBefore(Instant.parse(expectedEvent.occurredAt)))
                assertTrue(!Instant.parse(required(receiving.postedAt, "postedAt")).isBefore(Instant.parse(receiving.receivedAt)))
                val line = receiving.items.single { it.productId == expectedLine.productId }
                assertEquals(0, java.math.BigDecimal("0.20").compareTo(java.math.BigDecimal(required(line.markupRate, "receiving markupRate"))), "Fractional markup value")
                assertEquals(1L, line.tariffVersion)
                assertNotNull(line.tariffRuleId)
                Shop.audit(scope)
            }
        }
    }

    /** Одновременные копии поставки проходят через потребителей, но создают одну приёмку, одно исходящее сообщение и один приход. */
    @Test @AllureId("2") @DisplayName("Одновременный повтор поставки не удваивает приход")
    fun concurrentSupplierDuplicateCreatesOneReceipt() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            step("Отправляем одну поставку одновременно двумя запросами") { Shop.race({ Shop.publish(event) }, { Shop.publish(event) }) }
            step("Ожидаем оприходование и завершение обработки повторных сообщений") {
                Shop.received(event)
                Shop.stocked(scope, event)
                awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
                awaitConsumerDrain("warehouse.goods-posted", "store-goods-v1")
            }
            step("Проверяем один результат приёмки, одно сообщение и один приход") {
                assertEquals(10, Shop.requireStock(scope, event.payload.items.single().productId).availableQuantity)
                assertEquals("1", WarehouseOutboxDao.countByStoreId(scope.store).toString())
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Повтор исходного события и новая оболочка той же поставки сохраняют единственный приход. */
    @Test @AllureId("3") @DisplayName("Повтор оприходованной поставки с прежним и новым eventId не меняет остаток")
    fun goodsDuplicateWithNewEventIdNeverAddsCredit() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            step("Оприходуем исходную поставку") { Shop.supply(scope, event) }
            val goods = Shop.goods(event)
            val replay = goods.copy(eventId = UUID.randomUUID().toString())
            step("Повторяем исходное сообщение и его содержимое в новой оболочке") {
                Shop.kafka("warehouse.goods-posted", scope.store, JsonUtils.objectMapper.writeValueAsString(goods))
                Shop.kafka("warehouse.goods-posted", scope.store, JsonUtils.objectMapper.writeValueAsString(replay))
                awaitConsumerDrain("warehouse.goods-posted", "store-goods-v1")
            }
            step("Проверяем прежний остаток и единственный приход") {
                assertEquals(10, Shop.requireStock(scope, event.payload.items.single().productId).availableQuantity)
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Изменённые данные той же поставки сохраняются в диагностике и не перезаписывают принятую приёмку. */
    @Test @AllureId("4") @DisplayName("Изменённое содержимое повторной поставки не заменяет исходную приёмку")
    fun changedSupplierContentCannotOverwriteReceipt() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            step("Оприходуем исходную поставку из 10 единиц") { Shop.supply(scope, event) }
            val expectedReceiving = Shop.received(event)
            val changed = event.copy(eventId = UUID.randomUUID().toString(),
                payload = event.payload.copy(items = listOf(event.payload.items.single().copy(quantity = 99))))
            step("Повторяем идентификатор поставки с количеством 99") {
                Shop.publish(changed)
                awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
            }
            step("Проверяем сохранение исходной приёмки и наличие диагностики конфликта") {
                assertEquals(expectedReceiving, Shop.received(event))
                assertEquals(10, Shop.requireStock(scope, event.payload.items.single().productId).availableQuantity)
                assertEquals("1", DeliveryDiagnosticsDao.countByEventId(changed.eventId).toString())
                Shop.audit(scope)
            }
        }
    }

    /** STORE отклоняет изменённый повтор рассчитанной поставки, сохраняя исходный остаток и ошибочное сообщение в диагностике. */
    @Test @AllureId("41") @DisplayName("Изменённое сообщение оприходования не перезаписывает остаток")
    fun changedGoodsCannotOverwriteInventory() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            val expectedStock = step("Оприходуем исходную поставку") { Shop.supply(scope, event) }
            val goods = Shop.goods(event)
            val changed = goods.copy(eventId = UUID.randomUUID().toString(),
                payload = goods.payload.copy(items = listOf(goods.payload.items.single().copy(quantity = 99))))
            val raw = JsonUtils.objectMapper.writeValueAsString(changed)
            step("Отправляем изменённый приход под прежним идентификатором поставки") {
                Shop.kafka("warehouse.goods-posted", scope.store, raw)
                awaitConsumerDrain("warehouse.goods-posted", "store-goods-v1")
            }
            step("Проверяем сохранность остатка и диагностику исходного сообщения") {
                assertEquals(expectedStock, Shop.requireStock(scope, event.payload.items.single().productId))
                assertEquals("1", IncomingGoodsDiagnosticsDao.countByRawMessage(raw).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Некорректный текст сохраняется в диагностике Kafka без приёмки или прихода. Он содержит уникальный идентификатор магазина сценария. */
    @Test @AllureId("5") @DisplayName("Битое сообщение сохраняется в диагностике без прихода")
    fun malformedDeliveryHasDurableDiagnostic() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val raw = "{broken-${scope.store}"
            step("Отправляем заведомо битый JSON поставки") {
                Shop.kafka("logistics.deliveries", scope.store, raw)
                awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
            }
            step("Проверяем сохранённую диагностику и отсутствие товара") {
                assertEquals("1", DeliveryDiagnosticsDao.countByRawMessage(raw).toString())
                assertTrue(Shop.catalog(scope).items.isEmpty())
                Shop.audit(scope)
            }
        }
    }

    /** Неизвестная версия схемы не должна привести к приёмке, расчёту цены или приходу товара. */
    @Test @AllureId("51") @DisplayName("Неизвестная версия события диагностируется без приёмки")
    fun unknownVersionHasNoReceipt() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope).copy(schemaVersion = 42)
            val raw = JsonUtils.objectMapper.writeValueAsString(event)
            step("Отправляем поставку неизвестной версии через Kafka") {
                Shop.kafka("logistics.deliveries", scope.store, raw)
                awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
            }
            step("Проверяем диагностику и отсутствие приёмки и товара") {
                assertEquals("1", DeliveryDiagnosticsDao.countByRawMessage(raw).toString())
                assertNull(Shop.receiving(event))
                assertTrue(Shop.catalog(scope).items.isEmpty())
            }
        }
    }

    /** Поставка с нулевым количеством сохраняется как REJECTED с исходными данными. Цена не рассчитывается, приход не создаётся. */
    @Test @AllureId("52") @DisplayName("Поставка с нулевым количеством отклоняется без прихода")
    fun invalidQuantityIsRejected() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope, quantity = 0)
            step("Передаём поставку с нулевым количеством через Kafka") { Shop.kafka("logistics.deliveries", scope.store, JsonUtils.objectMapper.writeValueAsString(event)) }
            step("Проверяем отклонённую приёмку и сохранение причины отказа") {
                val rejected = Shop.received(event, DeliveryState.REJECTED)
                assertNotNull(rejected.rejectedPayload)
                assertNotNull(rejected.lastError)
                assertTrue(rejected.items.isEmpty())
                assertTrue(Shop.catalog(scope).items.isEmpty())
            }
        }
    }

    /** Повтор продукта в двух строках приводит к отклонению поставки, без произвольного выбора цены одной строки. */
    @Test @AllureId("32") @DisplayName("Две строки одного продукта отклоняются без неоднозначного прихода")
    fun duplicateProductLinesAreRejected() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val original = Shop.delivery(scope)
            val line = original.payload.items.single()
            val event = original.copy(payload = original.payload.copy(items = listOf(line, line.copy(lineId = "L-2"))))
            step("Передаём две строки одного продукта в одной поставке") { Shop.kafka("logistics.deliveries", scope.store, JsonUtils.objectMapper.writeValueAsString(event)) }
            step("Проверяем отклонение без исходящего события и остатка") {
                Shop.received(event, DeliveryState.REJECTED)
                assertEquals("0", WarehouseOutboxDao.countByStoreId(scope.store).toString())
                assertTrue(Shop.catalog(scope).items.isEmpty())
            }
        }
    }

    /** Пополнение сохраняет одну позицию остатка и меняет цену открытой корзины. Следующая поставка не меняет уже принятый состав заявки. */
    @Test @AllureId("28") @DisplayName("Пополнение меняет текущую цену, сохраняя цену принятой заявки")
    fun replenishmentRepricesStockAndFreezesAcceptedSnapshot() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val first = Shop.delivery(scope, quantity = 6)
            val stock = step("Оприходуем 6 единиц по 120.00 и добавляем 3 в корзину") { Shop.supply(scope, first) }
            val cart = Shop.filled(scope, stock, 3)
            val second = Shop.delivery(scope, product = stock.productId, quantity = 10, price = "120.00")
            step("Добавляем 10 единиц с новой продажной ценой 144.00") { Shop.publish(second); Shop.received(second) }
            step("Проверяем единую позицию из 16 единиц и актуальную сумму корзины 432.00") {
                val combined = Shop.stocked(scope, second, 16)
                assertEquals(stock.stockItemId, combined.stockItemId)
                assertEquals("144.00", combined.unitPrice)
                assertEquals("432.00", Shop.getCart(scope, cart).totalAmount)
            }
            val expectedSnapshot = step("Оформляем заявку по новой цене") {
                Shop.submit(scope, cart).expect(202)
                Shop.getCart(scope, cart)
            }
            val third = Shop.delivery(scope, product = stock.productId, quantity = 1, price = "200.00")
            step("Оприходуем ещё одну единицу с очередной новой ценой") { Shop.publish(third); Shop.received(third); Shop.stocked(scope, third, 14) }
            step("Проверяем неизменяемую сумму 432.00 принятой заявки и общий баланс") {
                assertEquals(expectedSnapshot, Shop.getCart(scope, cart))
                assertEquals("432.00", Shop.getCart(scope, cart).totalAmount)
                Shop.audit(scope)
            }
        }
    }

    /** Более новая поставка завершается первой. Завершение старой добавляет количество, но не откатывает текущую цену. */
    @Test @AllureId("9") @DisplayName("Позднее завершение старой поставки не откатывает цену новой")
    fun delayedOlderPricingCannotRevertPrice() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val older = Shop.delivery(scope, quantity = 6)
            step("Задерживаем расчёт первой поставки после сохранения её порядка") {
                resources.gate("warehouse", scope, "BEFORE_PRICING", older.payload.deliveryId)
                Shop.publish(older)
                Shop.reached("warehouse", scope, "BEFORE_PRICING")
            }
            val newer = Shop.delivery(scope, product = older.payload.items.single().productId, quantity = 10, price = "120.00")
            step("Оприходуем более новую поставку по цене 144.00 раньше первой") { Shop.publish(newer); Shop.received(newer); Shop.stocked(scope, newer, 10) }
            step("Разрешаем завершить старую поставку") { Shop.release("warehouse", scope); Shop.received(older) }
            step("Проверяем 16 единиц с новой ценой и исходный порядок обеих поставок") {
                assertEquals("144.00", Shop.stocked(scope, older, 16).unitPrice)
                assertEquals(1L, Shop.received(older).deliverySequence)
                assertEquals(2L, Shop.received(newer).deliverySequence)
                Shop.audit(scope)
            }
        }
    }

    /** Ручной повтор во время удержания активного расчёта не отбирает работу у обработчика и не создаёт второе оприходование. */
    @Test @AllureId("34") @DisplayName("Ручной retry активной попытки не создаёт второе оприходование")
    fun manualRetryCannotDuplicateActivePricing() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            step("Сохраняем поставку и задерживаем её активную попытку расчёта") {
                resources.gate("warehouse", scope, "BEFORE_PRICING", event.payload.deliveryId)
                Shop.publish(event)
                Shop.reached("warehouse", scope, "BEFORE_PRICING")
            }
            val expectedSequence = Shop.received(event, DeliveryState.WAITING_PRICING).deliverySequence
            step("Запрашиваем диагностический повтор во время активной попытки") {
                warehouseService.retryDelivery(scope.store, event.payload.deliveryId).expect(202)
            }
            step("Разрешаем автоматическому worker завершить расчёт") { Shop.release("warehouse", scope); Shop.received(event); Shop.stocked(scope, event) }
            step("Проверяем прежний порядок, единственное событие и приход") {
                assertEquals(expectedSequence, Shop.received(event).deliverySequence)
                assertEquals("1", WarehouseOutboxDao.countByStoreId(scope.store).toString())
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }
}
