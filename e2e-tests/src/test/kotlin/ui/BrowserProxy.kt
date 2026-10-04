package ui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import config.HttpClient
import constants.Endpoints
import io.qameta.allure.Allure
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Immutable browser request evidence; method/path/key/body remain separate correlation dimensions. */
data class BrowserRequest(val method: String, val path: String, val key: String?, val body: String, val headers: Map<String, List<String>>)
/** Exact forwarded response used only by the test-owned browser relay. */
private data class Forwarded(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)

/** Loopback-only fault relay for a single browser scenario; it forwards the real application and exposes no application control API. */
class BrowserProxy : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newFixedThreadPool(8)
    private val client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val evidence = CopyOnWriteArrayList<BrowserRequest>()
    private val cancelled = CopyOnWriteArrayList<String>()
    private val failure = AtomicReference<Throwable?>()
    private val fault = AtomicReference<((HttpExchange, BrowserRequest) -> Boolean)?>(null)
    val baseUrl = "http://127.0.0.1:${server.address.port}"

    init {
        try {
            server.executor = executor
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        } catch (problem: Throwable) {
            try { server.stop(0); executor.shutdownNow() } catch (cleanup: Throwable) { problem.addSuppressed(cleanup) }
            throw problem
        }
    }

    /** Forwards a concrete request and records infrastructure failure distinctly from an intentionally dropped browser exchange. */
    private fun handle(exchange: HttpExchange) {
        exchange.use {
            try {
                check(evidence.size < 2048) { "Browser evidence overflow" }
                val request = BrowserRequest(exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Idempotency-Key"), String(exchange.requestBody.readAllBytes(), Charsets.UTF_8),
                    exchange.requestHeaders.mapValues { it.value.toList() })
                evidence += request
                val intercept = fault.get()
                if (intercept != null && intercept(exchange, request)) return
                write(exchange, forward(exchange, request))
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
                try { exchange.sendResponseHeaders(502, -1) } catch (_: java.io.IOException) { /* Browser may already have disconnected. */ }
            }
        }
    }

    /** Preserves upstream HTTP semantics; warehouse routing is same-origin and rewrites only public discovery. */
    private fun forward(exchange: HttpExchange, request: BrowserRequest): Forwarded {
        val warehouse = request.path.startsWith("/warehouse/")
        val path = if (warehouse) exchange.requestURI.toString().removePrefix("/warehouse") else exchange.requestURI.toString()
        val base = if (warehouse) Endpoints.WAREHOUSE else Endpoints.STORE
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
        for ((name, values) in request.headers) {
            if (name.lowercase() in setOf("host", "connection", "content-length", "upgrade", "http2-settings", "origin", "accept-encoding")) continue
            values.forEach { builder.header(name, it) }
        }
        if (warehouse) builder.header("Origin", Endpoints.STORE)
        builder.method(request.method, if (request.body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(request.body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val content = if (request.path == "/ui/config" && response.statusCode() == 200) {
            HttpClient.mapper.writeValueAsBytes(UiConfiguration("$baseUrl/warehouse"))
        } else response.body()
        return Forwarded(response.statusCode(), response.headers().map(), content)
    }

    /** Writes a forwarded reply without stale transfer lengths or cross-origin headers from the upstream relay hop. */
    private fun write(exchange: HttpExchange, response: Forwarded) {
        for ((name, values) in response.headers) {
            if (name.lowercase() in setOf("content-length", "transfer-encoding", "connection", "access-control-allow-origin")) continue
            exchange.responseHeaders[name] = values
        }
        try {
            exchange.sendResponseHeaders(response.status, if (response.body.isEmpty()) -1 else response.body.size.toLong())
            if (response.body.isNotEmpty()) exchange.responseBody.write(response.body)
        } catch (disconnected: java.io.IOException) {
            // Only downstream writes are allowed to be cancelled; upstream network/parser failures still fail the scenario.
            cancelled += "${exchange.requestMethod} ${exchange.requestURI}: ${disconnected.message}"
        }
    }

    /** Drops only this exact POST before it reaches the application; observation was installed before the UI action. */
    fun loseRequestBeforeCommit(path: String) {
        fault.set { exchange, request ->
            if (request.method == "POST" && request.path == path) { exchange.close(); true } else false
        }
    }

    /** Forwards and requires real 202, then drops only its reply; application acceptance is not stubbed. */
    fun loseReplyAfterCommit(path: String) {
        fault.set { exchange, request ->
            if (request.method == "POST" && request.path == path) {
                val response = forward(exchange, request)
                check(response.status == 202) { "Expected committed 202 for $path, got ${response.status}" }
                exchange.close()
                true
            } else false
        }
    }

    /** Forwards a real committed request and substitutes 503 only at the browser boundary. */
    fun rejectReplyAfterCommit(path: String) {
        fault.set { exchange, request ->
            if (request.method == "POST" && request.path == path) {
                val response = forward(exchange, request)
                check(response.status == 202) { "Expected committed 202 for $path, got ${response.status}" }
                write(exchange, Forwarded(503, mapOf("Content-Type" to listOf("application/json")), "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}".toByteArray()))
                true
            } else false
        }
    }

    /** Removes the installed fault before retry/reload; request evidence stays available for identity assertions. */
    fun releaseFault() {
        fault.set(null)
    }

    /** Selects only the explicitly correlated method/path, preserving multiplicity instead of returning an arbitrary first request. */
    fun requests(path: String, method: String = "POST"): List<BrowserRequest> {
        assertHealthy()
        return evidence.filter { it.method == method && it.path == path }
    }

    /** Returns complete healthy request evidence for forbidden-route/header checks without response interception. */
    fun allRequests(): List<BrowserRequest> {
        assertHealthy()
        return evidence.toList()
    }

    /** Propagates a relay background failure into the scenario; healthy absence cannot conceal failed observation. */
    fun assertHealthy() {
        failure.get()?.let { throw AssertionError("Browser relay failed", it) }
    }

    /** Attaches safe request evidence before close; all credentials in this educational app are absent from browser requests. */
    fun attachEvidence() {
        val safe = evidence.map { request -> request.copy(headers = request.headers.mapValues { (name, values) ->
            if (name.lowercase() in setOf("authorization", "cookie", "proxy-authorization")) listOf("[redacted]") else values
        }) }
        Allure.addAttachment("Browser requests", "application/json", HttpClient.mapper.writeValueAsString(safe))
        if (cancelled.isNotEmpty()) Allure.addAttachment("Cancelled downstream responses", cancelled.joinToString("\n"))
    }

    /** Releases the fault, stops owned HTTP workers and confirms termination; close never controls application containers. */
    override fun close() {
        releaseFault()
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Browser relay workers did not terminate" }
        client.shutdownNow()
        assertHealthy()
    }
}

/** Actual public discovery shape; the relay changes its URL to the owned same-origin warehouse route only. */
data class UiConfiguration(val warehouseBaseUrl: String)
