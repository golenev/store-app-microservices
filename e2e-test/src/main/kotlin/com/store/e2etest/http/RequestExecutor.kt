package com.store.e2etest.http

import io.restassured.RestAssured
import io.restassured.http.Method
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification

open class RequestExecutor<T : Any>(
    val path: String,
    baseUri: String = DEFAULT_BASE_URI
) : BaseSpecification(baseUri = baseUri) {

    protected fun getRequest(url: String, requestSpecification: RequestSpecification, expectedStatus: Int = 200): Response {
        val response: Response = prepareForRequest(requestSpecification)
            .request(Method.GET, baseUri + url)
        prepareForResponse(expectedStatus).validate(response)
        return response
    }

    protected fun postRequest(url: String, requestSpecification: RequestSpecification, expectedStatus: Int = 200): Response {
        val response: Response = prepareForRequest(requestSpecification)
            .request(Method.POST, baseUri + url)
        prepareForResponse(expectedStatus).validate(response)
        return response
    }

    protected fun request(): RequestSpecification = RestAssured.given()

    companion object {
        const val DEFAULT_BASE_URI = "http://localhost:6790"
    }
}
