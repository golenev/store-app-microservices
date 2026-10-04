package org.golenev.restapi.config

import io.restassured.RestAssured
import io.restassured.config.HttpClientConfig
import io.restassured.config.RedirectConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.specification.RequestSpecification
import io.qameta.allure.restassured.AllureRestAssured

/** Настройки Rest Assured принадлежат запросу; общий RestAssured.config и состояние соседних сценариев не изменяются. */
open class BaseSpecification(private val baseUri: String) {
    /** Создаёт свежую спецификацию JSON с ограниченными ожиданиями соединения/ответа и вложениями запроса/ответа в Allure. */
    protected fun baseRequest(): RequestSpecification {
        val config = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig().setParam("http.connection.timeout", 5000)
                .setParam("http.socket.timeout", 10000))
            .redirect(RedirectConfig.redirectConfig().followRedirects(false))
        return RestAssured.given().baseUri(baseUri).config(config).accept("application/json")
            .filter(AllureRestAssured().setRequestAttachmentName("Запрос HTTP").setResponseAttachmentName("Ответ HTTP"))
    }
}
