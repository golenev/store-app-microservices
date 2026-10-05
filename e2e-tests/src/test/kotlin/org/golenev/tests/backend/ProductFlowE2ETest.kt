package org.golenev.tests.backend

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.qameta.allure.AllureId
import org.golenev.commondto.DeliveryState
import org.golenev.db.tables.deliveryDiagnostics.DeliveryDiagnosticsDao
import org.golenev.db.tables.incomingGoodsDiagnostics.IncomingGoodsDiagnosticsDao
import org.golenev.db.tables.inventory.InventoryDao
import org.golenev.db.tables.processedEvents.ProcessedEventsDao
import org.golenev.db.tables.stockExpenses.StockExpensesDao
import org.golenev.db.tables.stockMovements.StockMovementsDao
import org.golenev.db.tables.submissions.SubmissionsDao
import org.golenev.db.tables.warehouseOutbox.WarehouseOutboxDao
import org.golenev.restapi.endpoints.WarehouseServiceDao
import org.golenev.utils.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/** Настоящая цепочка приёмки, расчёта и прихода. Подготовка не создаёт остаток напрямую и не подменяет проверяемый результат. */
@DisplayName("Поставки, оприходование и порядок цен")
class ProductFlowE2ETest {

    private val kafkaProducer = org.golenev.utils.kafka.KafkaProducerImpl()

    private val warehouseService = WarehouseServiceDao()

    /** Корректная поставка сохраняет данные товара и получает порядок, время и правило расчёта до прихода в STORE. */
    @Test @AllureId("1") @DisplayName("Поставка сохраняет данные товара и оприходуется по рассчитанной цене")
    fun realSupplyPreservesReceivingMetadata() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val expectedEvent = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, quantity = 10, price = "100.00")
        }
        val expectedLine = expectedEvent.payload.items.single()
        val stock = step("Отправляем поставку и ожидаем её оприходование в магазине") { Shop.supply(scope, expectedEvent) }
        step("Проверяем цену, количество и неизменяемые сведения первой приёмки") {
            stock.productId.shouldBe(expectedLine.productId)
            stock.shortName.shouldBe(expectedLine.shortName)
            stock.description.shouldBe(expectedLine.description)
            stock.unitPrice.shouldBe("120.00")
            stock.availableQuantity.shouldBe(10)
            val receiving = Shop.received(expectedEvent)
            receiving.storeId.shouldBe(scope.store)
            receiving.deliveryId.shouldBe(expectedEvent.payload.deliveryId)
            receiving.deliverySequence.shouldBe(1L)
            (!Instant.parse(receiving.receivedAt).isBefore(Instant.parse(expectedEvent.occurredAt))).shouldBeTrue()
            (!Instant.parse(receiving.postedAt.shouldNotBeNull()).isBefore(Instant.parse(receiving.receivedAt))).shouldBeTrue()
            val line = receiving.items.single { it.productId == expectedLine.productId }
            withClue("Fractional markup value") { (java.math.BigDecimal("0.20").compareTo(java.math.BigDecimal(line.markupRate.shouldNotBeNull()))).shouldBe(0) }
            line.tariffVersion.shouldBe(1L)
            line.tariffRuleId.shouldNotBeNull()
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Повтор исходного события и новая оболочка той же поставки сохраняют единственный приход. */
    @Test @AllureId("3") @DisplayName("Повтор оприходованной поставки с прежним и новым eventId не меняет остаток")
    fun goodsDuplicateWithNewEventIdNeverAddsCredit() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope)
        }
        step("Оприходуем исходную поставку") { Shop.supply(scope, event) }
        val goods = step("Получаем результат оприходования поставки") {
            Shop.goods(event)
        }
        val replay = step("Подготавливаем данные поставки") {
            goods.copy(eventId = UUID.randomUUID().toString())
        }
        step("Повторяем исходное сообщение и его содержимое в новой оболочке") {
            kafkaProducer.sendMessage("warehouse.goods-posted", scope.store, JsonUtils.objectMapper.writeValueAsString(goods))
            kafkaProducer.sendMessage("warehouse.goods-posted", scope.store, JsonUtils.objectMapper.writeValueAsString(replay))

        }
        step("Проверяем прежний остаток и единственный приход") {
            awaitPoll {
                (ProcessedEventsDao.findByEventId(replay.eventId).size).shouldBe(1)
            }

            (Shop.stock(scope, event.payload.items.single().productId).shouldNotBeNull().availableQuantity).shouldBe(10)
            (StockMovementsDao.findByStoreId(scope.store).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Изменённые данные той же поставки сохраняются в диагностике и не перезаписывают принятую приёмку. */
    @Test @AllureId("4") @DisplayName("Изменённое содержимое повторной поставки не заменяет исходную приёмку")
    fun changedSupplierContentCannotOverwriteReceipt() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope)
        }
        step("Оприходуем исходную поставку из 10 единиц") { Shop.supply(scope, event) }
        val expectedReceiving = step("Получаем результат оприходования поставки") {
            Shop.received(event)
        }
        val changed = step("Подготавливаем данные поставки") {
            event.copy(eventId = UUID.randomUUID().toString(),
            payload = event.payload.copy(items = listOf(event.payload.items.single().copy(quantity = 99))))
        }
        step("Повторяем идентификатор поставки с количеством 99") {
            Shop.publish(changed)

        }
        step("Проверяем сохранение исходной приёмки и наличие диагностики конфликта") {
            awaitPoll {
                (DeliveryDiagnosticsDao.findByEventId(changed.eventId).size).shouldBe(1)
            }

            (Shop.received(event)).shouldBe(expectedReceiving)
            (Shop.stock(scope, event.payload.items.single().productId).shouldNotBeNull().availableQuantity).shouldBe(10)
            (DeliveryDiagnosticsDao.findByEventId(changed.eventId).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** STORE отклоняет изменённый повтор рассчитанной поставки, сохраняя исходный остаток и ошибочное сообщение в диагностике. */
    @Test @AllureId("41") @DisplayName("Изменённое сообщение оприходования не перезаписывает остаток")
    fun changedGoodsCannotOverwriteInventory() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope)
        }
        val expectedStock = step("Оприходуем исходную поставку") { Shop.supply(scope, event) }
        val goods = step("Получаем результат оприходования поставки") {
            Shop.goods(event)
        }
        val changed = step("Подготавливаем данные поставки") {
            goods.copy(eventId = UUID.randomUUID().toString(),
            payload = goods.payload.copy(items = listOf(goods.payload.items.single().copy(quantity = 99))))
        }
        val raw = JsonUtils.objectMapper.writeValueAsString(changed)
        step("Отправляем изменённый приход под прежним идентификатором поставки") {
            kafkaProducer.sendMessage("warehouse.goods-posted", scope.store, raw)

        }
        step("Проверяем сохранность остатка и диагностику исходного сообщения") {
            awaitPoll {
                (IncomingGoodsDiagnosticsDao.findByRawMessage(raw).size).shouldBe(1)
            }

            (Shop.stock(scope, event.payload.items.single().productId).shouldNotBeNull()).shouldBe(expectedStock)
            (IncomingGoodsDiagnosticsDao.findByRawMessage(raw).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Некорректный текст сохраняется в диагностике Kafka без приёмки или прихода. Он содержит уникальный идентификатор магазина сценария. */
    @Test @AllureId("5") @DisplayName("Битое сообщение сохраняется в диагностике без прихода")
    fun malformedDeliveryHasDurableDiagnostic() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val raw = "{broken-${scope.store}"
        step("Отправляем заведомо битый JSON поставки") {
            kafkaProducer.sendMessage("logistics.deliveries", scope.store, raw)

        }
        step("Проверяем сохранённую диагностику и отсутствие товара") {
            awaitPoll {
                (DeliveryDiagnosticsDao.findByRawMessage(raw).size).shouldBe(1)
            }

            (DeliveryDiagnosticsDao.findByRawMessage(raw).size).shouldBe(1)
            (Shop.catalog(scope).items.isEmpty()).shouldBeTrue()
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Неизвестная версия схемы сохраняется в диагностике; чтение отсутствующей приёмки возвращает 404, каталог остаётся пустым. */
    @Test @AllureId("51") @DisplayName("Неизвестная версия события диагностируется без приёмки")
    fun unknownVersionHasNoReceipt() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope).copy(schemaVersion = 42)
        }
        val raw = JsonUtils.objectMapper.writeValueAsString(event)
        step("Отправляем поставку неизвестной версии через Kafka") {
            kafkaProducer.sendMessage("logistics.deliveries", scope.store, raw)

        }
        step("Проверяем диагностику и отсутствие приёмки и товара") {
            awaitPoll {
                (DeliveryDiagnosticsDao.findByRawMessage(raw).size).shouldBe(1)
            }

            (DeliveryDiagnosticsDao.findByRawMessage(raw).size).shouldBe(1)
            warehouseService.getDelivery(event.storeId, event.payload.deliveryId, expectedStatus = 404)
            (Shop.catalog(scope).items.isEmpty()).shouldBeTrue()
        }
    }

    /** Поставка с нулевым количеством сохраняется как REJECTED с исходными данными. Цена не рассчитывается, приход не создаётся. */
    @Test @AllureId("52") @DisplayName("Поставка с нулевым количеством отклоняется без прихода")
    fun invalidQuantityIsRejected() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, quantity = 0)
        }
        step("Передаём поставку с нулевым количеством через Kafka") { kafkaProducer.sendMessage("logistics.deliveries", scope.store, JsonUtils.objectMapper.writeValueAsString(event)) }
        step("Проверяем отклонённую приёмку и сохранение причины отказа") {
            val rejected = Shop.received(event, DeliveryState.REJECTED)
            rejected.rejectedPayload.shouldNotBeNull()
            rejected.lastError.shouldNotBeNull()
            (rejected.items.isEmpty()).shouldBeTrue()
            (Shop.catalog(scope).items.isEmpty()).shouldBeTrue()
        }
    }

    /** Повтор продукта в двух строках приводит к отклонению поставки, без произвольного выбора цены одной строки. */
    @Test @AllureId("32") @DisplayName("Две строки одного продукта отклоняются без неоднозначного прихода")
    fun duplicateProductLinesAreRejected() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val original = step("Подготавливаем данные поставки") {
            Shop.delivery(scope)
        }
        val line = original.payload.items.single()
        val event = step("Подготавливаем данные поставки") {
            original.copy(payload = original.payload.copy(items = listOf(line, line.copy(lineId = "L-2"))))
        }
        step("Передаём две строки одного продукта в одной поставке") { kafkaProducer.sendMessage("logistics.deliveries", scope.store, JsonUtils.objectMapper.writeValueAsString(event)) }
        step("Проверяем отклонение без исходящего события и остатка") {
            Shop.received(event, DeliveryState.REJECTED)
            (WarehouseOutboxDao.findByStoreId(scope.store).size).shouldBe(0)
            (Shop.catalog(scope).items.isEmpty()).shouldBeTrue()
        }
    }

    /** Пополнение сохраняет одну позицию остатка и меняет цену открытой корзины. Следующая поставка не меняет уже принятый состав заявки. */
    @Test @AllureId("28") @DisplayName("Пополнение меняет текущую цену, сохраняя цену принятой заявки")
    fun replenishmentRepricesStockAndFreezesAcceptedSnapshot() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val first = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, quantity = 6)
        }
        val stock = step("Оприходуем 6 единиц по 120.00 и добавляем 3 в корзину") { Shop.supply(scope, first) }
        val cart = step("Наполняем корзину доступным товаром") {
            Shop.filled(scope, stock, 3)
        }
        val second = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, product = stock.productId, quantity = 10, price = "120.00")
        }
        step("Добавляем 10 единиц с новой продажной ценой 144.00") { Shop.publish(second); Shop.received(second) }
        step("Проверяем единую позицию из 16 единиц и актуальную сумму корзины 432.00") {
            val combined = Shop.stocked(scope, second, 16)
            combined.stockItemId.shouldBe(stock.stockItemId)
            combined.unitPrice.shouldBe("144.00")
            (Shop.getCart(scope, cart).totalAmount).shouldBe("432.00")
        }
        val expectedSnapshot = step("Оформляем заявку по новой цене") {
            Shop.submit(scope, cart)
            Shop.getCart(scope, cart)
        }
        val third = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, product = stock.productId, quantity = 1, price = "200.00")
        }
        step("Оприходуем ещё одну единицу с очередной новой ценой") { Shop.publish(third); Shop.received(third); Shop.stocked(scope, third, 14) }
        step("Проверяем неизменяемую сумму 432.00 принятой заявки и общий баланс") {
            (Shop.getCart(scope, cart)).shouldBe(expectedSnapshot)
            (Shop.getCart(scope, cart).totalAmount).shouldBe("432.00")
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

}
