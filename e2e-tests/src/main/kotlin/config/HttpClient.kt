package config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.qameta.allure.Allure
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals

/** Raw transport evidence; a caller explicitly decodes a valid DTO or a contractual error. */
data class Reply(val status: Int, val raw: String, val headers: Map<String, List<String>>) {
    /** Requires the caller's expected transport status before decoding an incompatible body. */
    fun expect(expectedStatus: Int): Reply {
        assertEquals(expectedStatus, status, "HTTP status; response=$raw")
        return this
    }

    /** Parses the declared wire contract without converting absent fields into empty/default values. */
    inline fun <reified T> body(): T = HttpClient.mapper.readValue(raw)
}

/** Stateless JSON transport; resource clients and scenarios own their assertions and identities. */
object HttpClient {
    val mapper = jacksonObjectMapper()
    private val transport = RestAssuredConfig.client()

    /** Sends exact JSON with a bounded deadline and nested request/response evidence; transport failures propagate. */
    fun request(base: String, path: String, method: String = "GET", body: Any? = null, key: String? = null): Reply {
        return Allure.step("$method $path", Allure.ThrowableRunnable<Reply> {
            val raw = when (body) {
                null -> ""
                is String -> body
                else -> mapper.writeValueAsString(body)
            }
            val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
            if (body != null) builder.header("Content-Type", "application/json")
            if (key != null) builder.header("Idempotency-Key", key)
            builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(raw))
            Allure.addAttachment("Request $method $path", "application/json", raw)
            val response = transport.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            Allure.addAttachment("Response HTTP ${response.statusCode()}", "application/json", response.body())
            Allure.addAttachment("Headers", response.headers().map().toString())
            Reply(response.statusCode(), response.body(), response.headers().map())
        })
    }
}
