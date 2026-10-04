package stageTests

import awaitState
import config.HttpClient
import constants.Endpoints
import helpers.*
import models.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Tariff rules use UUID cities; global cache/outage actions are serial and restored by the owned Template. */
@DisplayName("Тарифы, кеш и восстановление расчёта")
class TariffApiE2ETest {
    /** Builds valid explicit CRUD input, preserving a genuinely null unbounded upper interval. */
    private fun rule(city: String, rate: String): RuleInput {
        return RuleInput("NON_FOOD", city, "RUB", "0.00", null, rate)
    }

    /** Returns raw quote transport outcome so the scenario can explicitly select success or a contractual negative status. */
    private fun quote(city: String, price: String = "100.00"): config.Reply {
        return HttpClient.request(Endpoints.TARIFFS, "/tariffs/quote?productType=NON_FOOD&purchasePrice=$price&currency=RUB&cityId=$city")
    }

    /** CRUD leaves an existing quote snapshot until reset; only a subsequent real delivery reprices STORE inventory. */
    @Test @AllureId("10") @DisplayName("Сброс кеша обновляет следующие расчёты, но не цены принятого остатка")
    fun cacheSnapshotRequiresResetAndNewSupply() {
        withShopTemplate { resources ->
            val city = resources.city()
            val scope = resources.scope(city)
            val created = step("Создаём правило наценки 20% для независимого города") {
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules", "POST", rule(city, "0.20")).expect(201).body<TariffRule>()
            }
            val first = Shop.delivery(scope)
            val stock = step("Заполняем кеш и оприходуем 10 единиц по цене 120.00") {
                assertEquals("0.20", quote(city).expect(200).body<Quote>().markupRate)
                Shop.supply(scope, first)
            }
            step("Изменяем правило на 30% без сброса кеша") {
                val updated = HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules/${created.tariffRuleId}", "PUT", rule(city, "0.30")).expect(200).body<TariffRule>()
                assertEquals(created.tariffRuleId, updated.tariffRuleId)
                assertEquals(2L, updated.version)
            }
            step("Проверяем прежний кешированный коэффициент и версию") {
                val actual = quote(city).expect(200).body<Quote>()
                assertEquals("0.20", actual.markupRate)
                assertEquals(1L, actual.tariffVersion)
            }
            step("Сбрасываем кеш и проверяем новые расчёты без переоценки остатка") {
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/cache/reset", "POST").expect(200)
                val actual = quote(city).expect(200).body<Quote>()
                assertEquals("0.30", actual.markupRate)
                assertEquals(2L, actual.tariffVersion)
                assertEquals("120.00", Shop.requireStock(scope, stock.productId).unitPrice)
            }
            val second = Shop.delivery(scope, product = stock.productId, quantity = 1)
            step("Оприходуем следующую поставку с новой наценкой") { Shop.publish(second); Shop.received(second) }
            step("Проверяем общие 11 единиц по новой цене 130.00 и прежний идентификатор позиции") {
                val actual = Shop.stocked(scope, second, 11)
                assertEquals(stock.stockItemId, actual.stockItemId)
                assertEquals("130.00", actual.unitPrice)
                Shop.audit(scope)
            }
            step("Удаляем созданное правило и проверяем отсутствие в CRUD") {
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules/${created.tariffRuleId}", "DELETE").expect(204)
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules/${created.tariffRuleId}").expect(404)
            }
        }
    }

    /** Equivalent boundary inputs each select one [lower,upper) rule and keep their own parameterized report identity. */
    @ParameterizedTest(name = "Закупочная цена {0}: коэффициент {1}") @AllureId("12")
    @CsvSource("0.01,0.20", "499.99,0.20", "500.00,0.25", "999.99,0.25", "1000.00,0.30", "1000.01,0.30")
    fun tariffBoundarySelectsOneRule(price: String, expectedRate: String) {
        withShopTemplate { _ ->
            val actual = step("Рассчитываем наценку в Москве на границе диапазона: $price") { quote("MOSCOW", price).expect(200).body<Quote>() }
            step("Проверяем единственный ожидаемый коэффициент $expectedRate") { assertEquals(expectedRate, actual.markupRate) }
        }
    }

    /** No rule for a unique city produces a contractual missing-rule error rather than a fabricated zero rate. */
    @Test @AllureId("121") @DisplayName("Отсутствующее правило возвращает понятную ошибку")
    fun absentRuleIsAnError() {
        withShopTemplate { resources ->
            val city = resources.city()
            val error = step("Запрашиваем расчёт для города без правил") { quote(city).expect(404).body<ApiError>() }
            step("Проверяем отказ из-за отсутствующего тарифа") { assertEquals("TARIFF_NOT_FOUND", error.code) }
        }
    }

    /** Overlapping CRUD rules remain valid diagnostic data, but a quote must reject ambiguity rather than choosing an arbitrary winner. */
    @Test @AllureId("122") @DisplayName("Пересекающиеся правила не дают произвольный коэффициент")
    fun ambiguousRulesAreAnError() {
        withShopTemplate { resources ->
            val city = resources.city()
            step("Создаём два пересекающихся правила 20% и 30%") {
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules", "POST", rule(city, "0.20")).expect(201)
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules", "POST", rule(city, "0.30")).expect(201)
            }
            val error = step("Запрашиваем наценку в пересекающемся диапазоне") { quote(city).expect(409).body<ApiError>() }
            step("Проверяем отказ из-за неоднозначного тарифа") { assertEquals("TARIFF_AMBIGUOUS", error.code) }
        }
    }

    /** Real FOOD pricing reaches a half-cent boundary and credits exactly 0.51 after HALF_UP rounding. */
    @Test @AllureId("123") @DisplayName("Полкопейки округляются HALF_UP до цены 0.51")
    fun fractionalPricingRoundsHalfUp() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val expectedPrice = "0.51"
            val event = Shop.delivery(scope, price = "0.50", type = "FOOD")
            val actual = step("Оприходуем продовольственный товар с закупочной ценой 0.50") { Shop.supply(scope, event) }
            step("Проверяем округление 0.505 до 0.51 без floating point") {
                assertEquals(expectedPrice, actual.unitPrice)
                Shop.audit(scope)
            }
        }
    }

    /** Stopping actual Redis preserves database quote fallback and the end-to-end credit; cleanup restores Redis even after setup failure. */
    @Test @AllureId("13") @DisplayName("Отказ Redis сохраняет расчёт через БД и приход в магазин")
    fun redisOutageAllowsDatabasePricingFallback() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope, price = "123.45")
            step("Отключаем кеш тарифов") { resources.stop("redis", Endpoints.TARIFFS) }
            val actual = step("Запрашиваем тариф и оприходуем поставку при недоступном кеше") {
                assertEquals("0.20", quote("MOSCOW", "123.45").expect(200).body<Quote>().markupRate)
                Shop.supply(scope, event)
            }
            step("Проверяем цену 148.14 и сохранённый приход") {
                assertEquals("148.14", actual.unitPrice)
                Shop.audit(scope)
            }
            step("Восстанавливаем кеш тарифов") { Shop.control("start", "redis"); Shop.healthy(Endpoints.TARIFFS) }
        }
    }

    /** A missing rule leaves persisted WAITING_PRICING; creating that rule makes the automatic worker finish once without manual retry. */
    @Test @AllureId("33") @DisplayName("Добавление отсутствующего правила автоматически завершает ожидающую поставку")
    fun newRuleAutomaticallyUnblocksWaitingDelivery() {
        withShopTemplate { resources ->
            val city = resources.city()
            val scope = resources.scope(city)
            val event = Shop.delivery(scope)
            step("Передаём поставку для города без тарифного правила") {
                Shop.publish(event)
                awaitState("missing rule delivery=${event.payload.deliveryId}", read = { Shop.receiving(event) }, ready = { it?.lastError != null })
            }
            step("Проверяем ожидание расчёта без преждевременного прихода") {
                assertEquals(DeliveryState.WAITING_PRICING, Shop.receiving(event)?.state)
                assertTrue(Shop.catalog(scope).items.isEmpty())
            }
            val created = step("Добавляем корректное правило и сбрасываем кеш") {
                val created = HttpClient.request(Endpoints.TARIFFS, "/tariffs/rules", "POST", rule(city, "0.20")).expect(201).body<TariffRule>()
                HttpClient.request(Endpoints.TARIFFS, "/tariffs/cache/reset", "POST").expect(200)
                created
            }
            step("Проверяем автоматический расчёт по новому правилу и один приход") {
                val posted = Shop.received(event)
                assertEquals(created.tariffRuleId, posted.items.single().tariffRuleId)
                assertEquals(1L, posted.items.single().tariffVersion)
                assertEquals("120.00", Shop.stocked(scope, event).unitPrice)
                Shop.audit(scope)
            }
        }
    }
}
