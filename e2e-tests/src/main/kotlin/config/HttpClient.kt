package config

import io.restassured.response.Response

object HttpClient {
    fun get(
        url: String,
        headers: Map<String, Any> = emptyMap(),
        params: Map<String, Any> = emptyMap(),
        baseUri: String? = null
    ): Response {
        val spec = RestAssuredConfig.given()
            .headers(headers)
            .params(params)
        baseUri?.let { spec.baseUri(it) }
        return spec.get(url)
    }

    fun post(
        url: String,
        body: Any? = null,
        headers: Map<String, Any> = emptyMap(),
        baseUri: String? = null
    ): Response {
        val spec = RestAssuredConfig.given()
            .headers(headers)
        baseUri?.let { spec.baseUri(it) }
        if (body != null) {
            spec.contentType("application/json").body(body)
        }
        return spec.post(url)
    }
}
