package org.golenev.restapi.config

import io.qameta.allure.restassured.AllureRestAssured
import io.restassured.RestAssured
import io.restassured.config.ObjectMapperConfig
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.golenev.utils.JsonUtils

/** Подготовка Rest Assured по референсу: общий mapper JsonUtils, журналирование и вложения запросов/ответов. */
open class BaseSpecification(protected val baseUri: String) {
    init {
        RestAssured.config = RestAssured.config().objectMapperConfig(
            ObjectMapperConfig().jackson2ObjectMapperFactory { _, _ -> JsonUtils.objectMapper }
        )
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    /** Дополняет спецификацию адресом сервиса и JSON-контрактом; сохраняет запрос и ответ во вложениях Allure. */
    protected fun prepareForRequest(requestSpecification: RequestSpecification): RequestSpecification =
        requestSpecification
            .baseUri(baseUri)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .log().all()
            .filter(AllureRestAssured())

    /** Создаёт проверку явно ожидаемого HTTP-статуса; бизнес-поля проверяются в тесте. */
    protected fun prepareForResponse(expectedStatus: Int): ResponseValidator = ResponseValidator(expectedStatus)
}
