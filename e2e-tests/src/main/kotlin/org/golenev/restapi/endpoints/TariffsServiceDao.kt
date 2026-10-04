package org.golenev.restapi.endpoints

import io.restassured.response.Response
import org.golenev.commondto.RuleInput
import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor

/** REST-операции сервиса TARIFFS на заданном адресе; каждый запрос имеет собственную спецификацию и явно ожидаемый статус. */
class TariffsServiceDao : RequestExecutor(Environment.TARIFFS_URL) {
    /** Создаёт правило по явно переданному контракту; ожидаемый статус задаёт сценарий. */
    @io.qameta.allure.Step("Создаём тарифное правило")
    fun createRule(body: RuleInput, expectedStatus: Int = 201): Response = postRequest("/tariffs/rules", baseRequest().body(body), expectedStatus)

    /** Изменяет существующее правило; сброс кеша является отдельным действием сценария. */
    @io.qameta.allure.Step("Изменяем тарифное правило {ruleId}")
    fun updateRule(ruleId: String, body: RuleInput, expectedStatus: Int = 200): Response = putRequest("/tariffs/rules/$ruleId", baseRequest().body(body), expectedStatus)

    /** Читает конкретное правило для проверки его наличия либо удаления. */
    @io.qameta.allure.Step("Читаем тарифное правило {ruleId}")
    fun getRule(ruleId: String, expectedStatus: Int = 200): Response = getRequest("/tariffs/rules/$ruleId", baseRequest(), expectedStatus)

    /** Удаляет конкретное правило, не затрагивая правила соседних сценариев. */
    @io.qameta.allure.Step("Удаляем тарифное правило {ruleId}")
    fun deleteRule(ruleId: String, expectedStatus: Int = 204): Response = deleteRequest("/tariffs/rules/$ruleId", baseRequest(), expectedStatus)

    /** Сбрасывает общий кеш тарифов; остальные данные Redis не затрагиваются. */
    @io.qameta.allure.Step("Сбрасываем тарифный кеш")
    fun resetCache(expectedStatus: Int = 200): Response = postRequest("/tariffs/cache/reset", baseRequest(), expectedStatus)
    /** Рассчитывает наценку для заданного города и закупочной цены; ожидаемый успех или отказ проверяется централизованно. */
    @io.qameta.allure.Step("Рассчитываем наценку в городе {city}")
    fun getQuote(city: String, price: String = "100.00", expectedStatus: Int = 200): Response =
        getRequest("/tariffs/quote", baseRequest().queryParam("productType", "NON_FOOD")
            .queryParam("purchasePrice", price).queryParam("currency", "RUB").queryParam("cityId", city), expectedStatus)
}
