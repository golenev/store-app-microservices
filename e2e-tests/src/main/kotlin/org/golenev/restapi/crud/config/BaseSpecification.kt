package org.golenev.restapi.crud.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.qameta.allure.restassured.AllureRestAssured
import io.restassured.RestAssured
import io.restassured.config.ObjectMapperConfig
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.golenev.config.Environment

/** BaseSpecification нового CRUD-набора, перенесённый из golenev-xlsx-report-system/e2e-test. */
open class BaseSpecification(
    protected val baseUri: String = (System.getenv("PYRAMID_TARIFFS_URL") ?: Environment.TARIFFS_URL),
) {

    init {
        RestAssured.config = RestAssured.config().objectMapperConfig(
            ObjectMapperConfig().jackson2ObjectMapperFactory { _, _ ->
                jacksonObjectMapper()
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        )
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    /** Дополняет переданную спецификацию адресом, JSON, журналированием и вложениями Allure; возвращает её для отправки. */
    protected fun prepareForRequest(requestSpecification: RequestSpecification): RequestSpecification {
        return requestSpecification
            .baseUri(baseUri)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .log().all()
            .filter(AllureRestAssured())
    }

    /** Создаёт проверку заданного HTTP-статуса; бизнес-поля проверяются в сценарии. */
    protected fun prepareForResponse(expectedStatus: Int): ResponseValidator =
       ResponseValidator(expectedStatus)
}
