package config

import java.net.http.HttpClient
import java.time.Duration

/** Historical filename retained; bounded JDK HTTP transport replaces global mutable RestAssured configuration. */
object RestAssuredConfig {
    /** Builds a thread-safe transport with bounded connect time and no redirects/authentication/cookie state. */
    fun client(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
}
