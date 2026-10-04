package org.golenev.tests.backend

import org.golenev.utils.awaitState
import org.golenev.restapi.endpoints.*
import org.golenev.config.Environment
import org.golenev.utils.*
import org.golenev.commondto.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
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
    private fun quote(city: String, price: String = "100.00"): org.golenev.restapi.config.Reply {
        return tariffsService.request("/tariffs/quote?productType=NON_FOOD&purchasePrice=$price&currency=RUB&cityId=$city")
    }

    /** Изменение правила не обновляет заполненный кеш до сброса. Цена STORE меняется только после следующей настоящей поставки. */
    @Test @AllureId("10") @DisplayName("Сброс кеша обновляет следующие расчёты, но не цены принятого остатка")
    fun cacheSnapshotRequiresResetAndNewSupply() {
        withShopTemplate { resources ->
            val city = resources.city()
            val scope = resources.scope(city)
            val created = step("Создаём правило наценки 20% для независимого города") {
                tariffsService.createRule(rule(city, "0.20")).expect(201).body<TariffRule>()
            }
            val first = Shop.delivery(scope)
            val stock = step("Заполняем кеш и оприходуем 10 единиц по цене 120.00") {
                assertEquals("0.20", quote(city).expect(200).body<Quote>().markupRate)
                Shop.supply(scope, first)
            }
            step("Изменяем правило на 30% без сброса кеша") {
                val updated = tariffsService.updateRule(created.tariffRuleId, rule(city, "0.30")).expect(200).body<TariffRule>()
                assertEquals(created.tariffRuleId, updated.tariffRuleId)
                assertEquals(2L, updated.version)
            }
            step("Проверяем прежний кешированный коэффициент и версию") {
                val actual = quote(city).expect(200).body<Quote>()
                assertEquals("0.20", actual.markupRate)
                assertEquals(1L, actual.tariffVersion)
            }
            step("Сбрасываем кеш и проверяем новые расчёты без переоценки остатка") {
                tariffsService.resetCache().expect(200)
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
                tariffsService.deleteRule(created.tariffRuleId).expect(204)
                tariffsService.getRule(created.tariffRuleId).expect(404)
            }
        }
    }

    /** Каждая граничная цена выбирает одно правило диапазона [нижняя граница, верхняя граница). Варианты имеют отдельные записи параметризованного отчёта. */
    @ParameterizedTest(name = "Закупочная цена {0}: коэффициент {1}") @AllureId("12")
    @CsvSource("0.01,0.20", "499.99,0.20", "500.00,0.25", "999.99,0.25", "1000.00,0.30", "1000.01,0.30")
    fun tariffBoundarySelectsOneRule(price: String, expectedRate: String) {
        withShopTemplate { _ ->
            val actual = step("Рассчитываем наценку в Москве на границе диапазона: $price") { quote("MOSCOW", price).expect(200).body<Quote>() }
            step("Проверяем единственный ожидаемый коэффициент $expectedRate") { assertEquals(expectedRate, actual.markupRate) }
        }
    }

    /** Для уникального города без правил API возвращает ошибку отсутствующего тарифа, а не выдуманную нулевую наценку. */
    @Test @AllureId("121") @DisplayName("Отсутствующее правило возвращает понятную ошибку")
    fun absentRuleIsAnError() {
        withShopTemplate { resources ->
            val city = resources.city()
            val error = step("Запрашиваем расчёт для города без правил") { quote(city).expect(404).body<ApiError>() }
            step("Проверяем отказ из-за отсутствующего тарифа") { assertEquals("TARIFF_NOT_FOUND", error.code) }
        }
    }

    /** Пересекающиеся правила допускаются для диагностики. Расчёт наценки отклоняет неоднозначность, без произвольного выбора правила. */
    @Test @AllureId("122") @DisplayName("Пересекающиеся правила не дают произвольный коэффициент")
    fun ambiguousRulesAreAnError() {
        withShopTemplate { resources ->
            val city = resources.city()
            step("Создаём два пересекающихся правила 20% и 30%") {
                tariffsService.createRule(rule(city, "0.20")).expect(201)
                tariffsService.createRule(rule(city, "0.30")).expect(201)
            }
            val error = step("Запрашиваем наценку в пересекающемся диапазоне") { quote(city).expect(409).body<ApiError>() }
            step("Проверяем отказ из-за неоднозначного тарифа") { assertEquals("TARIFF_AMBIGUOUS", error.code) }
        }
    }

    /** Расчёт цены FOOD достигает половины копейки. Округление HALF_UP должно дать ровно 0.51. */
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

    /** Остановка Redis сохраняет расчёт через БД и реальный приход товара. Очистка восстанавливает Redis даже при ошибке подготовки. */
    @Test @AllureId("13") @DisplayName("Отказ Redis сохраняет расчёт через БД и приход в магазин")
    fun redisOutageAllowsDatabasePricingFallback() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val event = Shop.delivery(scope, price = "123.45")
            step("Отключаем кеш тарифов") { resources.stop("redis", Environment.TARIFFS_URL) }
            val actual = step("Запрашиваем тариф и оприходуем поставку при недоступном кеше") {
                assertEquals("0.20", quote("MOSCOW", "123.45").expect(200).body<Quote>().markupRate)
                Shop.supply(scope, event)
            }
            step("Проверяем цену 148.14 и сохранённый приход") {
                assertEquals("148.14", actual.unitPrice)
                Shop.audit(scope)
            }
            step("Восстанавливаем кеш тарифов") { Shop.control("start", "redis"); Shop.healthy(Environment.TARIFFS_URL) }
        }
    }

    /** Без правила поставка сохраняется в WAITING_PRICING. Создание правила позволяет автоматическому обработчику завершить её один раз, без ручного повтора. */
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
                val created = tariffsService.createRule(rule(city, "0.20")).expect(201).body<TariffRule>()
                tariffsService.resetCache().expect(200)
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
