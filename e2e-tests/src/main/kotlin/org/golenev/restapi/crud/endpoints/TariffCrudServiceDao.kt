package org.golenev.restapi.crud.endpoints

import io.qameta.allure.Step
import io.restassured.response.Response
import org.golenev.commondto.RuleInput
import org.golenev.commondto.TariffRulesResponse
import org.golenev.restapi.crud.config.Paths
import org.golenev.restapi.crud.config.RequestExecutor

/** CRUD TARIFFS по структуре ReportServiceDao: типизированные запросы, конкретные методы и исходные Response. */
class TariffCrudServiceDao : RequestExecutor<Unit>(
    path = Paths.TARIFF_RULES.path,
) {

    /** Создаёт правило из RuleInput; ожидаемый 201 либо статус отказа задаёт сценарий. */
    @Step("POST /tariffs/rules: создаём правило, ожидаем статус {expectedStatus}")
    fun createRule(request: RuleInput, expectedStatus: Int = 201): Response {
        return postRequest(
            url = path,
            requestSpecification = baseRequest().body(request),
            expectedStatus = expectedStatus,
        )
    }

    /** Читает UUID через API; статус 200 или ожидаемая ошибка проверяются до возврата ответа. */
    @Step("GET /tariffs/rules/{ruleId}: читаем правило, ожидаем статус {expectedStatus}")
    fun getRule(ruleId: String, expectedStatus: Int = 200): Response {
        return getRequest(
            url = "$path/$ruleId",
            requestSpecification = baseRequest(),
            expectedStatus = expectedStatus,
        )
    }

    /** Полностью заменяет правило через PUT, передавая upperBound=null явно в JSON. */
    @Step("PUT /tariffs/rules/{ruleId}: заменяем условия, ожидаем статус {expectedStatus}")
    fun updateRule(ruleId: String, request: RuleInput, expectedStatus: Int = 200): Response {
        return putRequest(
            url = "$path/$ruleId",
            requestSpecification = baseRequest().body(request),
            expectedStatus = expectedStatus,
        )
    }

    /** Удаляет только выбранный UUID; статус 204 либо ожидаемая ошибка задаётся сценарием. */
    @Step("DELETE /tariffs/rules/{ruleId}: удаляем правило, ожидаем статус {expectedStatus}")
    fun deleteRule(ruleId: String, expectedStatus: Int = 204): Response {
        return deleteRequest(
            url = "$path/$ruleId",
            requestSpecification = baseRequest(),
            expectedStatus = expectedStatus,
        )
    }

    /** Читает публичный список через HTTP и разбирает его в data class; обращения к SQL отсутствуют. */
    @Step("GET /tariffs/rules: читаем список правил")
    fun getRules(): TariffRulesResponse {
        return getRequest(
            url = path,
            requestSpecification = baseRequest(),
        ).`as`(TariffRulesResponse::class.java)
    }
}
