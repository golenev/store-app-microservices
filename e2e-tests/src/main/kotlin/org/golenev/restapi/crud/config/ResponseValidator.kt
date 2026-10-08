package org.golenev.restapi.crud.config

import io.restassured.response.Response

/** ResponseValidator нового CRUD-набора, перенесённый из golenev-xlsx-report-system/e2e-test. */
class ResponseValidator(private val expectedStatus: Int) {
    /** Проверяет ожидаемый статус исходного Response средствами Rest Assured и выводит ответ в журнал. */
    fun validate(response: Response) {
        response.then()
            .log().all()
            .statusCode(expectedStatus)
    }
}
