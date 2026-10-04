package config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.qameta.allure.Allure
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Status plus immutable parsed JSON; negative responses are observable without swallowing transport failures. */
data class Reply(val status: Int, val json: JsonNode, val raw: String)

/** Scoped, stateless JSON requests with exact decimal strings and Allure request/response evidence. */
object HttpClient {
    val mapper = jacksonObjectMapper()
    private val transport = RestAssuredConfig.client()

    /** Sends the provided HTTP method/body/key with a 10-second deadline; failures propagate and never synthesize success. */
    fun request(base: String, path: String, method: String = "GET", body: Any? = null, key: String? = null): Reply {
        val raw = if (body == null) "" else if (body is String) body else mapper.writeValueAsString(body)
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
        if (body != null) builder.header("Content-Type", "application/json")
        if (key != null) builder.header("Idempotency-Key", key)
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(raw))
        Allure.addAttachment("$method $path", "application/json", raw)
        val response = transport.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        Allure.addAttachment("HTTP ${response.statusCode()} $path", "application/json", response.body())
        val json = if (response.body().isBlank()) mapper.nullNode() else mapper.readTree(response.body())
        return Reply(response.statusCode(), json, response.body())
    }
}
