package org.golenev.pyramid.restapi.config

import io.restassured.builder.RequestSpecBuilder
import io.restassured.config.HttpClientConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.specification.RequestSpecification

/** Собственные настройки HTTP для нового набора; глобальные настройки Rest Assured и защищённого E2E не изменяются. */
class BaseSpecification(private val baseUrl: String) {
    /**
     * Создаёт новую спецификацию для одного запроса: JSON и ограниченные ожидания сети.
     * Соединение ждёт до трёх секунд, ответ — до десяти; отсутствие приложения приводит к ошибке теста.
     */
    fun create(): RequestSpecification = RequestSpecBuilder().setBaseUri(baseUrl)
        .setContentType("application/json")
        .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", 3000).setParam("http.socket.timeout", 10000)))
        .build()
}
