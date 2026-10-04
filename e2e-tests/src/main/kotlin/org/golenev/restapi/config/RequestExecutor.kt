package org.golenev.restapi.config

import io.restassured.RestAssured
import io.restassured.http.Method
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification

/** Отправляет конкретные HTTP-методы по референсу; тело, заголовки и ожидаемый статус задаёт сервисный DAO. */
open class RequestExecutor(baseUri: String) : BaseSpecification(baseUri) {

    /** Универсальный метод для отправки запросов, устраняющий дублирование кода. */
    protected fun executeRequest(
        method: Method,
        url: String,
        requestSpecification: RequestSpecification,
        expectedStatus: Int = 200
    ): Response = prepareForRequest(requestSpecification)
        .request(method, baseUri + url)
        .also { response -> prepareForResponse(expectedStatus).validate(response) }

    /** Отправляет GET с переданной спецификацией, проверяет статус и возвращает исходный ответ. */
    protected fun getRequest(url: String, spec: RequestSpecification, expectedStatus: Int = 200): Response =
        executeRequest(Method.GET, url, spec, expectedStatus)

    /** Отправляет POST с телом и заголовками DAO; статус проверяется до возврата ответа сценарию. */
    protected fun postRequest(url: String, spec: RequestSpecification, expectedStatus: Int = 200): Response =
        executeRequest(Method.POST, url, spec, expectedStatus)

    /** Отправляет PUT с явно подготовленными данными изменения и проверяет ожидаемый статус. */
    protected fun putRequest(url: String, spec: RequestSpecification, expectedStatus: Int = 200): Response =
        executeRequest(Method.PUT, url, spec, expectedStatus)

    /** Отправляет DELETE для ресурса и проверяет ожидаемый статус удаления или отказа. */
    protected fun deleteRequest(url: String, spec: RequestSpecification, expectedStatus: Int = 200): Response =
        executeRequest(Method.DELETE, url, spec, expectedStatus)

    /** Возвращает свежую спецификацию, которую DAO дополняет параметрами запроса. */
    protected fun baseRequest(): RequestSpecification = RestAssured.given()
}
