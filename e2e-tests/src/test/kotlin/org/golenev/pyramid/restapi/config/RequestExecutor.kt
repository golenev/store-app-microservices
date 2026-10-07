package org.golenev.pyramid.restapi.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.http.Method
import org.golenev.pyramid.restapi.PyramidResponse

/** Выполняет только внешние HTTP-запросы; не имеет доступа к SQL, Kafka и компонентам приложения. */
class RequestExecutor(private val specification: BaseSpecification) {
    private val json = ObjectMapper()

    /**
     * Отправляет запрос с собственной спецификацией, сериализует карту полей с явными null.
     * @param method HTTP-метод
     * @param path путь публичного API
     * @param body поля запроса или null для отсутствующего тела
     * @return фактические статус, тело и Location без автоматического объявления запроса успешным
     */
    fun execute(method: String, path: String, body: Map<String, Any?>?): PyramidResponse {
        val request = given().spec(specification.create())
        if (body != null) request.body(json.writeValueAsString(body))
        val response = request.request(Method.valueOf(method), path)
        return PyramidResponse(response.statusCode(), response.asString(), response.getHeader("Location"))
    }
}
