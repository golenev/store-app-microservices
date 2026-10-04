package org.golenev.tests.backend

import org.golenev.db.tables.warehouseOutbox.WarehouseOutboxDao
import org.golenev.db.tables.storeOutbox.StoreOutboxDao
import org.golenev.db.tables.stockExpenses.StockExpensesDao
import org.golenev.db.tables.stockMovements.StockMovementsDao
import org.golenev.utils.awaitState
import org.golenev.utils.required
import org.golenev.restapi.endpoints.*
import org.golenev.config.Environment
import org.golenev.utils.*
import org.golenev.commondto.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** Настоящие отказы сервисов и брокера с независимыми наблюдателями и обязательным восстановлением окружения. */
@DisplayName("Автоматическое восстановление и повтор публикации")
class KafkaTariffTest {
    /** Отказ TARIFFS сохраняет неудачную попытку расчёта без прихода. Восстановление сервиса автоматически продолжает обработку поставки. */
    @Test @AllureId("6") @DisplayName("Возвращение сервиса тарифов автоматически завершает ожидающий расчёт")
    fun pricingRecoversTariffsOutageAutomatically() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            step("Останавливаем сервис тарифов и передаём поставку") {
                resources.stop("tariffs-service", Environment.TARIFFS_URL)
                Shop.publish(event)
            }
            step("Проверяем сохранённую попытку расчёта и отсутствие прихода") {
                val waiting = awaitState("failed pricing delivery=${event.payload.deliveryId}", read = { Shop.receiving(event) }, ready = { it?.lastError != null })
                assertEquals(DeliveryState.WAITING_PRICING, waiting?.state)
                assertTrue(required(waiting, "waiting receiving").attemptCount >= 1)
                assertTrue(Shop.catalog(scope).items.isEmpty())
            }
            step("Восстанавливаем доступность тарифов без ручного retry поставки") { Shop.control("start", "tariffs-service"); Shop.healthy(Environment.TARIFFS_URL) }
            step("Проверяем автоматическое оприходование 10 единиц по 120.00") {
                Shop.received(event)
                assertEquals("120.00", Shop.stocked(scope, event).unitPrice)
                Shop.audit(scope)
            }
        }
    }

    /** Перезапуск во время удержания WAITING_PRICING сохраняет исходное время и порядок приёмки и создаёт один приход. */
    @Test @AllureId("7") @DisplayName("Рестарт склада восстанавливает ожидающую поставку с прежней идентичностью")
    fun restartRecoversWaitingPricingWithoutChangingIdentity() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            val expectedWaiting = step("Задерживаем активный расчёт после сохранения поставки") {
                resources.gate("warehouse", scope, "BEFORE_PRICING", event.payload.deliveryId)
                Shop.publish(event)
                Shop.reached("warehouse", scope, "BEFORE_PRICING")
                Shop.received(event, DeliveryState.WAITING_PRICING)
            }
            step("Останавливаем склад, снимаем задержку и запускаем его с прежней БД") {
                resources.stop("warehouse-service", Environment.WAREHOUSE_URL)
                Shop.release("warehouse", scope)
                Shop.control("start", "warehouse-service")
                Shop.healthy(Environment.WAREHOUSE_URL)
            }
            step("Проверяем продолжение расчёта с прежним временем и порядком первой приёмки") {
                val actual = Shop.received(event)
                assertEquals(expectedWaiting.receivedAt, actual.receivedAt)
                assertEquals(expectedWaiting.deliverySequence, actual.deliverySequence)
                assertEquals(event.payload.deliveryId, actual.deliveryId)
                Shop.stocked(scope, event)
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Отказ Kafka после расчёта оставляет сообщение PENDING и сохранённую ошибку. Восстановление публикует исходный результат расчёта. */
    @Test @AllureId("25") @DisplayName("Приход автоматически передаётся после восстановления Kafka без нового расчёта")
    fun warehouseOutboxRecoversBrokerOutage() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            val expectedPosted = step("Рассчитываем поставку, задерживая отправку результата") {
                resources.gate("warehouse", scope, "BEFORE_PUBLISH")
                Shop.publish(event)
                val posted = Shop.received(event)
                Shop.reached("warehouse", scope, "BEFORE_PUBLISH")
                posted
            }
            val expectedGoods = Shop.goods(event)
            step("Отключаем Kafka и разрешаем попытку отправки") { resources.pauseKafka(); Shop.release("warehouse", scope) }
            step("Проверяем ожидающее сообщение с сохранённой ошибкой публикации") {
                awaitState("warehouse publication error store=${scope.store}", read = {
                    WarehouseOutboxDao.findLastErrorByStoreId(scope.store)
                }, ready = { it != null })
                assertEquals("PENDING", WarehouseOutboxDao.findPublicationStatusByStoreId(scope.store))
            }
            step("Восстанавливаем Kafka и ожидаем приход в магазине") { resources.resumeKafka(); Shop.stocked(scope, event) }
            step("Проверяем исходные цены, время расчёта и единственное движение") {
                assertEquals(expectedPosted.postedAt, Shop.received(event).postedAt)
                assertEquals(expectedGoods, Shop.goods(event))
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Принятое списание сохраняется при отказе Kafka. Возобновление публикации не запускает вторую транзакцию списания. */
    @Test @AllureId("251") @DisplayName("Принятая заявка публикуется после возвращения Kafka без повторного списания")
    fun acceptedExpenseRecoversBrokerOutage() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и готовим корзину с 3 единицами") { Shop.supply(scope) }
            val cart = Shop.filled(scope, stock, 3)
            step("Отключаем Kafka до оформления заявки") { resources.pauseKafka() }
            val accepted = step("Принимаем заявку при недоступной публикации") { Shop.submit(scope, cart).expect(202).body<Submission>() }
            step("Проверяем сохранённую ошибку отправки и уже выполненный расход") {
                awaitState("STORE publication error store=${scope.store}", read = {
                    StoreOutboxDao.findLastErrorByStoreId(scope.store)
                }, ready = { it != null })
                assertEquals("PENDING", StoreOutboxDao.findPublicationStatusByStoreId(scope.store))
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
            }
            step("Восстанавливаем Kafka и ожидаем автоматическую передачу заявки") { resources.resumeKafka(); Shop.published(scope, accepted) }
            step("Проверяем прежний остаток и единственное списание") {
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("1", StockExpensesDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** WAREHOUSE перезапускается после фиксации POSTED, до отправки захваченного сообщения. Его данные и единственный приход сохраняются. */
    @Test @AllureId("26") @DisplayName("Рестарт после расчёта до отправки сохраняет исходный приход")
    fun warehouseRestartAfterCommitBeforePublication() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            val expectedPosted = step("Рассчитываем поставку и задерживаем её передачу магазину") {
                resources.gate("warehouse", scope, "BEFORE_PUBLISH")
                Shop.publish(event)
                val posted = Shop.received(event)
                Shop.reached("warehouse", scope, "BEFORE_PUBLISH")
                assertTrue(Shop.catalog(scope).items.isEmpty())
                posted
            }
            val expectedGoods = Shop.goods(event)
            step("Перезапускаем склад после commit до публикации") {
                resources.stop("warehouse-service", Environment.WAREHOUSE_URL)
                Shop.release("warehouse", scope)
                Shop.control("start", "warehouse-service")
                Shop.healthy(Environment.WAREHOUSE_URL)
            }
            step("Проверяем доставку прежнего результата и единственное движение") {
                Shop.stocked(scope, event)
                assertEquals(expectedPosted, Shop.received(event))
                assertEquals(expectedGoods, Shop.goods(event))
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** STORE перезапускается после списания, до отправки захваченного сообщения. Исходные ключ и версия возвращают ту же операцию. */
    @Test @AllureId("261") @DisplayName("Рестарт после списания до публикации не создаёт новый расход")
    fun storeRestartAfterCommitBeforePublication() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и создаём корзину с 3 единицами") { Shop.supply(scope) }
            val cart = Shop.filled(scope, stock, 3)
            val key = UUID.randomUUID().toString()
            val accepted = step("Принимаем заявку, задерживая отправку её сообщения") {
                resources.gate("store", scope, "BEFORE_PUBLISH")
                val accepted = Shop.submit(scope, cart, key).expect(202).body<Submission>()
                Shop.reached("store", scope, "BEFORE_PUBLISH")
                accepted
            }
            val expectedSnapshot = Shop.getCart(scope, cart)
            step("Перезапускаем магазин после commit до отправки заявки") {
                resources.stop("store-service", Environment.STORE_URL)
                Shop.release("store", scope)
                Shop.control("start", "store-service")
                Shop.healthy(Environment.STORE_URL)
            }
            step("Проверяем восстановленную публикацию, прежний snapshot и повтор исходного ключа") {
                Shop.published(scope, accepted)
                assertEquals(expectedSnapshot, Shop.getCart(scope, cart))
                assertEquals(accepted.submissionId, Shop.submit(scope, cart, key).expect(202).body<Submission>().submissionId)
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
                Shop.audit(scope)
            }
        }
    }

    /** Kafka подтверждает событие WAREHOUSE до потери отметки PUBLISHED. После рестарта допустимы одинаковые физические копии, но приход STORE остаётся один. */
    @Test @AllureId("27") @DisplayName("Потерянная отметка передачи прихода допускает повтор сообщения без нового прихода")
    fun warehouseAckLossReplaysExactGoods() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope)
            val observer = resources.observe("warehouse.goods-posted", scope.store)
            step("Задерживаем сохранение отметки после подтверждения Kafka") { resources.gate("warehouse", scope, "AFTER_ACK") }
            val eventId = step("Оприходуем поставку и получаем подтверждённое брокером событие") {
                Shop.publish(event)
                Shop.received(event)
                val id = Shop.reached("warehouse", scope, "AFTER_ACK")
                Shop.stocked(scope, event)
                id
            }
            val expectedRaw = required(WarehouseOutboxDao.findPayloadByStoreId(scope.store), "saved GoodsPosted")
            step("Перезапускаем склад с потерянной отметкой передачи") {
                resources.stop("warehouse-service", Environment.WAREHOUSE_URL)
                Shop.release("warehouse", scope)
                Shop.control("start", "warehouse-service")
                Shop.healthy(Environment.WAREHOUSE_URL)
            }
            step("Проверяем минимум две идентичные физические записи и один логический приход") {
                val copies = observer.copies(eventId, 2)
                copies.forEach { assertEquals(expectedRaw, it.raw, "GoodsPosted partition=${it.partition} offset=${it.offset}") }
                awaitConsumerDrain("warehouse.goods-posted", "store-goods-v1")
                assertEquals(10, Shop.requireStock(scope, event.payload.items.single().productId).availableQuantity)
                assertEquals("1", StockMovementsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Kafka подтверждает событие STORE до потери отметки PUBLISHED. Рестарт повторяет сохранённую заявку без нового списания. */
    @Test @AllureId("271") @DisplayName("Повтор после потери отметки отправки заявки сохраняет один расход")
    fun storeAckLossReplaysExactOrder() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и создаём корзину с 3 единицами") { Shop.supply(scope) }
            val cart = Shop.filled(scope, stock, 3)
            val observer = resources.observe("store.order-submitted", scope.store)
            val accepted = step("Принимаем заявку с задержкой сохранения отметки после Kafka ack") {
                resources.gate("store", scope, "AFTER_ACK")
                val accepted = Shop.submit(scope, cart).expect(202).body<Submission>()
                Shop.reached("store", scope, "AFTER_ACK")
                assertEquals("PENDING", StoreOutboxDao.findPublicationStatusByStoreId(scope.store))
                accepted
            }
            val expectedRaw = required(StoreOutboxDao.findPayloadByStoreId(scope.store), "saved OrderSubmitted")
            step("Перезапускаем магазин с потерянной отметкой передачи") {
                resources.stop("store-service", Environment.STORE_URL)
                Shop.release("store", scope)
                Shop.control("start", "store-service")
                Shop.healthy(Environment.STORE_URL)
            }
            step("Проверяем минимум две идентичные записи, опубликованную операцию и один расход") {
                observer.copies(accepted.eventId, 2).forEach { assertEquals(expectedRaw, it.raw, "OrderSubmitted offset=${it.offset}") }
                Shop.published(scope, accepted)
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("1", StockExpensesDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }
}
