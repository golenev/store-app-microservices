package org.golenev.restapi.config

import io.restassured.response.Response

/** Проверяет HTTP-статус средствами Rest Assured по образцу проекта golenev-xlsx-report-system. */
class ResponseValidator(private val expectedStatus: Int) {
    /** Проверяет статус исходного ответа; при несовпадении Rest Assured завершает тест с диагностикой. */
    fun validate(response: Response) {
        response.then().log().all().statusCode(expectedStatus)
    }
}
