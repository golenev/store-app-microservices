package org.golenev.ui.config

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.golenev.utils.JsonUtils
import org.golenev.restapi.endpoints.*
import org.golenev.config.Environment
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


/** Локальный прокси управляемых сетевых сбоев одного сценария. Передаёт запросы настоящему приложению и не добавляет управляющих маршрутов в его API. */
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

    /** Передаёт конкретный запрос и сохраняет ошибку инфраструктуры отдельно от намеренно оборванного обмена с браузером. */
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
                try { exchange.sendResponseHeaders(502, -1) } catch (_: java.io.IOException) { /* Браузер мог уже закрыть соединение; повторно отправить ему ответ в этом случае невозможно. */ }
            }
        }
    }

    /** Сохраняет HTTP-семантику настоящего сервиса. Запросы WAREHOUSE проходят через тот же источник; меняется только публичный адрес в конфигурации UI. */
    private fun forward(exchange: HttpExchange, request: BrowserRequest): Forwarded {
        val warehouse = request.path.startsWith("/warehouse/")
        val path = if (warehouse) exchange.requestURI.toString().removePrefix("/warehouse") else exchange.requestURI.toString()
        val base = if (warehouse) Environment.WAREHOUSE_URL else Environment.STORE_URL
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
        for ((name, values) in request.headers) {
            if (name.lowercase() in setOf("host", "connection", "content-length", "upgrade", "http2-settings", "origin", "accept-encoding")) continue
            values.forEach { builder.header(name, it) }
        }
        if (warehouse) builder.header("Origin", Environment.STORE_URL)
        builder.method(request.method, if (request.body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(request.body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val content = if (request.path == "/ui/config" && response.statusCode() == 200) {
            JsonUtils.objectMapper.writeValueAsBytes(UiConfiguration("$baseUrl/warehouse"))
        } else response.body()
        return Forwarded(response.statusCode(), response.headers().map(), content)
    }

    /** Передаёт ответ без устаревшей длины и заголовков междоменных запросов предыдущего участка соединения. */
    private fun write(exchange: HttpExchange, response: Forwarded) {
        for ((name, values) in response.headers) {
            if (name.lowercase() in setOf("content-length", "transfer-encoding", "connection", "access-control-allow-origin")) continue
            exchange.responseHeaders[name] = values
        }
        try {
            exchange.sendResponseHeaders(response.status, if (response.body.isEmpty()) -1 else response.body.size.toLong())
            if (response.body.isNotEmpty()) exchange.responseBody.write(response.body)
        } catch (disconnected: java.io.IOException) {
            // Прерывается только отправка ответа браузеру. Ошибки подключения к сервису и разбора данных по-прежнему приводят к падению сценария.
            cancelled += "${exchange.requestMethod} ${exchange.requestURI}: ${disconnected.message}"
        }
    }

    /** Обрывает только POST по заданному пути до передачи в приложение. Наблюдение установлено до действия пользователя. */
    fun loseRequestBeforeCommit(path: String) {
        fault.set { exchange, request ->
            if (request.method == "POST" && request.path == path) { exchange.close(); true } else false
        }
    }

    /** Передаёт настоящий запрос, требует статус 202 и обрывает только ответ. Принятие операции приложением не подменяется. */
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

    /** Передаёт настоящий запрос до фиксации операции и заменяет ответ на 503 только на границе браузера. */
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

    /** Снимает сетевой сбой перед повтором или перезагрузкой. История запросов сохраняется для проверки идентичности операции. */
    fun releaseFault() {
        fault.set(null)
    }

    /** Выбирает запросы по заданным методу и пути, сохраняя все повторы вместо произвольного первого элемента. */
    fun requests(path: String, method: String = "POST"): List<BrowserRequest> {
        assertHealthy()
        return evidence.filter { it.method == method && it.path == path }
    }

    /** Возвращает полную историю запросов работающего наблюдателя для проверки запрещённых маршрутов и заголовков. */
    fun allRequests(): List<BrowserRequest> {
        assertHealthy()
        return evidence.toList()
    }

    /** Передаёт фоновую ошибку прокси в сценарий. Неисправное наблюдение не может выглядеть как отсутствие запросов. */
    fun assertHealthy() {
        failure.get()?.let { throw AssertionError("Browser relay failed", it) }
    }

    /** Прикладывает историю запросов к Allure до закрытия прокси. Значения заголовков авторизации и cookies скрываются. */
    fun attachEvidence() {
        val safe = evidence.map { request -> request.copy(headers = request.headers.mapValues { (name, values) ->
            if (name.lowercase() in setOf("authorization", "cookie", "proxy-authorization")) listOf("[redacted]") else values
        }) }
        Allure.addAttachment("Запросы браузера", "application/json", JsonUtils.objectMapper.writeValueAsString(safe))
        if (cancelled.isNotEmpty()) Allure.addAttachment("Ответы, отменённые браузером", cancelled.joinToString("\n"))
    }

    /** Снимает сбой, останавливает собственные HTTP-потоки и проверяет их завершение. Контейнеры приложения не управляются. */
    override fun close() {
        releaseFault()
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Browser relay workers did not terminate" }
        client.shutdownNow()
        assertHealthy()
    }
}
