package org.golenev.tests.backend


import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.qameta.allure.AllureId
import org.golenev.commondto.*
import org.golenev.db.tables.inventory.InventoryDao
import org.golenev.db.tables.stockExpenses.StockExpensesDao
import org.golenev.db.tables.stockMovements.StockMovementsDao
import org.golenev.db.tables.submissions.SubmissionsDao
import org.golenev.restapi.endpoints.TariffsServiceDao
import org.golenev.utils.Shop
import org.golenev.utils.awaitPoll
import org.golenev.utils.step
import org.golenev.utils.store
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Тарифные правила используют уникальные города. Общие сбросы кеша и отказы выполняются последовательно с обязательной очисткой. */
@DisplayName("Тарифы, кеш и восстановление расчёта")
class TariffApiE2ETest {

    private val tariffsService = TariffsServiceDao()

    /** Создаёт корректный запрос изменения правила. Null у верхней границы означает неограниченный диапазон. */
    private fun rule(city: String, rate: String): RuleInput {
        return RuleInput("NON_FOOD", city, "RUB", "0.00", null, rate)
    }

    /** Возвращает исходный HTTP-ответ расчёта наценки. Ожидаемый статус успеха или отказа явно задаёт сценарий. */
    private fun quote(city: String, price: String = "100.00", expectedStatus: Int = 200): io.restassured.response.Response {
        return tariffsService.getQuote(city, price, expectedStatus)
    }

    /** Изменение правила не обновляет заполненный кеш до сброса. Цена STORE меняется только после следующей настоящей поставки. */
    @Test @AllureId("10") @DisplayName("Сброс кеша обновляет следующие расчёты, но не цены принятого остатка")
    fun cacheSnapshotRequiresResetAndNewSupply() {
        val city = step("Подготавливаем город для собственного тарифного правила") {
            Shop.city()
        }
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope(city)
        }
        val created = step("Создаём правило наценки 20% для независимого города") {
            tariffsService.createRule(rule(city, "0.20")).`as`(TariffRule::class.java)
        }
        val first = step("Подготавливаем данные поставки") {
            Shop.delivery(scope)
        }
        val stock = step("Заполняем кеш и оприходуем 10 единиц по цене 120.00") {
            (quote(city).`as`(Quote::class.java).markupRate).shouldBe("0.20")
            Shop.supply(scope, first)
        }
        step("Изменяем правило на 30% без сброса кеша") {
            val updated = tariffsService.updateRule(created.tariffRuleId, rule(city, "0.30")).`as`(TariffRule::class.java)
            updated.tariffRuleId.shouldBe(created.tariffRuleId)
            updated.version.shouldBe(2L)
        }
        step("Проверяем прежний кешированный коэффициент и версию") {
            val actual = quote(city).`as`(Quote::class.java)
            actual.markupRate.shouldBe("0.20")
            actual.tariffVersion.shouldBe(1L)
        }
        step("Сбрасываем кеш и проверяем новые расчёты без переоценки остатка") {
            tariffsService.resetCache()
            val actual = quote(city).`as`(Quote::class.java)
            actual.markupRate.shouldBe("0.30")
            actual.tariffVersion.shouldBe(2L)
            (Shop.stock(scope, stock.productId).shouldNotBeNull().unitPrice).shouldBe("120.00")
        }
        val second = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, product = stock.productId, quantity = 1)
        }
        step("Оприходуем следующую поставку с новой наценкой") { Shop.publish(second); Shop.received(second) }
        step("Проверяем общие 11 единиц по новой цене 130.00 и прежний идентификатор позиции") {
            val actual = Shop.stocked(scope, second, 11)
            actual.stockItemId.shouldBe(stock.stockItemId)
            actual.unitPrice.shouldBe("130.00")
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.sumQuantityByStockItemId(row.stockItemId) ?: 0
                val spent = StockExpensesDao.sumQuantityByStockItemId(row.stockItemId) ?: 0
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
        step("Удаляем созданное правило и проверяем отсутствие в CRUD") {
            tariffsService.deleteRule(created.tariffRuleId)
            tariffsService.getRule(created.tariffRuleId, expectedStatus = 404)
        }
    }

    /** Каждая граничная цена выбирает одно правило диапазона [нижняя граница, верхняя граница). Варианты имеют отдельные записи параметризованного отчёта. */
    @ParameterizedTest(name = "Закупочная цена {0}: коэффициент {1}") @AllureId("12")
    @CsvSource("0.01,0.20", "499.99,0.20", "500.00,0.25", "999.99,0.25", "1000.00,0.30", "1000.01,0.30")
    fun tariffBoundarySelectsOneRule(price: String, expectedRate: String) {
        val actual = step("Рассчитываем наценку в Москве на границе диапазона: $price") { quote("MOSCOW", price).`as`(Quote::class.java) }
        step("Проверяем единственный ожидаемый коэффициент $expectedRate") { actual.markupRate.shouldBe(expectedRate) }
    }

    /** Для уникального города без правил API возвращает ошибку отсутствующего тарифа, а не выдуманную нулевую наценку. */
    @Test @AllureId("121") @DisplayName("Отсутствующее правило возвращает понятную ошибку")
    fun absentRuleIsAnError() {
        val city = step("Подготавливаем город для собственного тарифного правила") {
            Shop.city()
        }
        val error = step("Запрашиваем расчёт для города без правил") { quote(city, expectedStatus = 404).`as`(ApiError::class.java) }
        step("Проверяем отказ из-за отсутствующего тарифа") { error.code.shouldBe("TARIFF_NOT_FOUND") }
    }

    /** Пересекающиеся правила допускаются для диагностики. Расчёт наценки отклоняет неоднозначность, без произвольного выбора правила. */
    @Test @AllureId("122") @DisplayName("Пересекающиеся правила не дают произвольный коэффициент")
    fun ambiguousRulesAreAnError() {
        val city = step("Подготавливаем город для собственного тарифного правила") {
            Shop.city()
        }
        step("Создаём два пересекающихся правила 20% и 30%") {
            tariffsService.createRule(rule(city, "0.20"))
            tariffsService.createRule(rule(city, "0.30"))
        }
        val error = step("Запрашиваем наценку в пересекающемся диапазоне") { quote(city, expectedStatus = 409).`as`(ApiError::class.java) }
        step("Проверяем отказ из-за неоднозначного тарифа") { error.code.shouldBe("TARIFF_AMBIGUOUS") }
    }

    /** Расчёт цены FOOD достигает половины копейки. Округление HALF_UP должно дать ровно 0.51. */
    @Test @AllureId("123") @DisplayName("Полкопейки округляются HALF_UP до цены 0.51")
    fun fractionalPricingRoundsHalfUp() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val expectedPrice = "0.51"
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope, price = "0.50", type = "FOOD")
        }
        val actual = step("Оприходуем продовольственный товар с закупочной ценой 0.50") { Shop.supply(scope, event) }
        step("Проверяем округление половины копейки до 0.51") {
            actual.unitPrice.shouldBe(expectedPrice)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.sumQuantityByStockItemId(row.stockItemId) ?: 0
                val spent = StockExpensesDao.sumQuantityByStockItemId(row.stockItemId) ?: 0
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Без правила поставка сохраняется в WAITING_PRICING. Создание правила позволяет автоматическому обработчику завершить её один раз, без ручного повтора. */
    @Test @AllureId("33") @DisplayName("Добавление отсутствующего правила автоматически завершает ожидающую поставку")
    fun newRuleAutomaticallyUnblocksWaitingDelivery() {
        val city = step("Подготавливаем город для собственного тарифного правила") {
            Shop.city()
        }
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope(city)
        }
        val event = step("Подготавливаем данные поставки") {
            Shop.delivery(scope)
        }
        step("Передаём поставку для города без тарифного правила") {
            Shop.publish(event)
            awaitPoll {
                val actual = Shop.receiving(event)
                withClue("missing rule delivery=${event.payload.deliveryId}") { (actual?.lastError != null).shouldBeTrue() }
                actual
            }
        }
        step("Проверяем ожидание расчёта без преждевременного прихода") {
            (Shop.receiving(event)?.state).shouldBe(DeliveryState.WAITING_PRICING)
            (Shop.catalog(scope).items.isEmpty()).shouldBeTrue()
        }
        val created = step("Добавляем корректное правило и сбрасываем кеш") {
            val created = tariffsService.createRule(rule(city, "0.20")).`as`(TariffRule::class.java)
            tariffsService.resetCache()
            created
        }
        step("Проверяем автоматический расчёт по новому правилу и один приход") {
            val posted = Shop.received(event)
            (posted.items.single().tariffRuleId).shouldBe(created.tariffRuleId)
            (posted.items.single().tariffVersion).shouldBe(1L)
            (Shop.stocked(scope, event).unitPrice).shouldBe("120.00")
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.sumQuantityByStockItemId(row.stockItemId) ?: 0
                val spent = StockExpensesDao.sumQuantityByStockItemId(row.stockItemId) ?: 0
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }
}
