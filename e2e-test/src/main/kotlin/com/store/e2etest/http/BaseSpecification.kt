package com.store.e2etest.http

import io.qameta.allure.restassured.AllureRestAssured
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification

open class BaseSpecification(protected val baseUri: String) {

    init {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    protected fun prepareForRequest(requestSpecification: RequestSpecification): RequestSpecification {
        return requestSpecification
            .baseUri(baseUri)
            .contentType(ContentType.JSON)
            .filter(AllureRestAssured())
    }

    protected fun prepareForResponse(expectedStatus: Int): ResponseValidator = ResponseValidator(expectedStatus)
}

class ResponseValidator(private val expectedStatus: Int) {
    fun validate(response: Response) {
        response.then().statusCode(expectedStatus)
    }
}
