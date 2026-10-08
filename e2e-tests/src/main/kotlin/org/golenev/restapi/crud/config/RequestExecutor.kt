package org.golenev.restapi.crud.config

import io.restassured.RestAssured
import io.restassured.http.Method
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification

/** RequestExecutor нового CRUD-набора, перенесённый из golenev-xlsx-report-system/e2e-test. */
open class RequestExecutor<T : Any>(val path: String) : BaseSpecification() {

    /** Отправляет GET с заданной спецификацией, проверяет ожидаемый статус и возвращает исходный Response. */
    protected fun getRequest(url: String, requestSpecification: RequestSpecification, expectedStatus: Int = 200): Response {
        val response: Response = prepareForRequest(requestSpecification)
            .request(Method.GET, baseUri + url)
        prepareForResponse(expectedStatus)
            .validate(response)
        return response
    }

    /** Отправляет POST с типизированным телом, проверяет ожидаемый статус и возвращает исходный Response. */
    protected fun postRequest(url: String, requestSpecification: RequestSpecification, expectedStatus: Int = 200): Response {
        val response: Response = prepareForRequest(requestSpecification)
            .request(Method.POST, baseUri + url)
        prepareForResponse(expectedStatus)
            .validate(response)
        return response
    }

    /** Отправляет DELETE по указанному пути, проверяет ожидаемый статус и возвращает исходный Response. */
    protected fun deleteRequest(url: String, requestSpecification: RequestSpecification, expectedStatus: Int = 200): Response {
        val response: Response = prepareForRequest(requestSpecification)
            .request(Method.DELETE, baseUri + url)
        prepareForResponse(expectedStatus)
            .validate(response)
        return response
    }

    /** Создаёт свежую спецификацию Rest Assured без параметров предыдущих операций. */
    protected fun baseRequest(): RequestSpecification = RestAssured.given()

    /** Отправляет PUT полной замены по тому же образцу, что POST; проверяет статус и возвращает исходный Response. */
    protected fun putRequest(url: String, requestSpecification: RequestSpecification, expectedStatus: Int = 200): Response {
        val response: Response = prepareForRequest(requestSpecification)
            .request(Method.PUT, baseUri + url)
        prepareForResponse(expectedStatus)
            .validate(response)
        return response
    }

}
