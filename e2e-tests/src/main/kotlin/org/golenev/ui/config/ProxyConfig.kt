package org.golenev.ui.config

import com.codeborne.selenide.proxy.SelenideProxyServer
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Отклоняет первый POST по [endpoint] ответом 503 до передачи в сервис; последующие запросы пропускает.
 * Фильтр устанавливается на открытом прокси Selenide и возвращает уникальное имя для удаления в AfterEach.
 * Атомарный признак обеспечивает один отказ даже при нескольких запросах из браузера.
 * Пустой ответ содержит Content-Length: 0, поэтому клиент сразу завершает чтение тела,
 * не дожидаясь закрытия соединения или собственного сетевого тайм-аута.
 * BrowserUp добавляет фильтры запросов в начало цепочки: последний зарегистрированный выполняется первым.
 * Поэтому фильтр отказа устанавливают до interceptSubmissionKeys и interceptRequestBody,
 * чтобы наблюдатели увидели исходный запрос перед его отклонением. Повтор приложения проходит
 * через тот же зарегистрированный фильтр без нового отказа. В середине сценария фильтр не удаляется.
 */
fun rejectNextRequest(proxyServer: SelenideProxyServer, endpoint: String): String {
    val filterName = UUID.randomUUID().toString()
    val reject = AtomicBoolean(true)
    proxyServer.addRequestFilter(filterName) { request, _, info ->
        if (info.url.endsWith(endpoint) && request.method().name() == "POST" && reject.compareAndSet(true, false)) {
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE).also {
                HttpUtil.setContentLength(it, 0)
            }
        } else null
    }
    return filterName
}

/**
 * Подменяет статус и тело первого ответа на POST по [endpoint], сохраняя передачу исходного запроса в сервис.
 * [status] и [responseBody] задают ответ, который увидит браузер после завершения серверной операции.
 * Следующие ответы пропускаются без изменений: повтор может получить действительный результат.
 * Возвращает уникальное имя фильтра; тест сохраняет его и удаляет в AfterEach даже после падения.
 * Признак подмены атомарен, обработчик выполняется в потоке прокси и не содержит тестовых проверок.
 */
fun replaceNextResponseStatus(
    proxyServer: SelenideProxyServer,
    endpoint: String,
    status: Int,
    responseBody: String,
): String {
    val filterName = UUID.randomUUID().toString()
    val replace = AtomicBoolean(true)
    proxyServer.addResponseFilter(filterName) { response, contents, info ->
        if (info.url.endsWith(endpoint) && info.originalRequest.method().name() == "POST" && replace.compareAndSet(true, false)) {
            response.setStatus(HttpResponseStatus.valueOf(status))
            contents.textContents = responseBody
        }
    }
    return filterName
}

/**
 * Наблюдает запросы страниц во время [run] и возвращает снимок пар «путь запроса — заголовок Authorization».
 * Отсутствующий заголовок сохраняется как null, запросы проходят без изменений.
 * Потокобезопасный список принимает события прокси; действие должно дождаться загрузки страниц.
 * Временный фильтр снимается в finally, включая падение действия. Авторизацию и допустимость путей проверяет тест.
 */
fun interceptPageRequests(proxyServer: SelenideProxyServer, run: () -> Unit): List<Pair<String, String?>> {
    val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    val filterName = UUID.randomUUID().toString()
    proxyServer.addRequestFilter(filterName) { request, _, info ->
        requests += java.net.URI(info.url).path to request.headers().get("Authorization")
        null
    }
    return try {
        run()
        requests.toList()
    } finally {
        proxyServer.removeRequestFilter(filterName)
    }
}

/**
 * Собирает значения Idempotency-Key всех POST-запросов к указанному пути во время действия.
 *
 * Прокси должен принадлежать уже открытому браузеру Selenide. [endpoint] — полный путь
 * оформления одной корзины, без адреса сервера. Сравнение по окончанию URL исключает
 * запросы к другим корзинам; запросы с query-параметрами сюда не входят.
 * [run] выполняет исходное оформление и его повторы, включая проверки промежуточного состояния.
 * Запросы проходят без изменений. Отсутствующий заголовок записывается как null,
 * поэтому тест может явно проверить нарушение контракта, а не потерять такой запрос.
 *
 * Фильтр получает события в потоке прокси; список защищён от одновременной записи.
 * После завершения действия возвращается снимок заголовков в порядке наблюдения запросов.
 * Сама функция не ждёт отправки: действие должно дождаться результата через Selenide
 * либо interceptRequestBody. Фильтр снимается также при исключении; исключение действия
 * передаётся вызывающему тесту. Повторный вызов создаёт новый фильтр и новый список.
 */
fun interceptSubmissionKeys(
    proxyServer: SelenideProxyServer,
    endpoint: String,
    run: () -> Unit,
): List<String?> {
    val keys = java.util.concurrent.CopyOnWriteArrayList<String?>()
    val filterName = UUID.randomUUID().toString()
    proxyServer.addRequestFilter(filterName) { request, _, info ->
        if (info.url.endsWith(endpoint) && request.method().name() == "POST") {
            keys += request.headers().get("Idempotency-Key")
        }
        null
    }
    return try {
        run()
        keys.toList()
    } finally {
        proxyServer.removeRequestFilter(filterName)
    }
}

/**
 * Наблюдает первый POST по [endpoint] во время [run] и возвращает его тело.
 * Действие выполняется со своими ограничениями Selenide; после него ожидание ещё не
 * перехваченного тела ограничено 15 секундами. Временный фильтр снимается в finally
 * при успехе, ошибке действия и тайм-ауте, поэтому следующий вызов не наследует наблюдателя.
 */
fun interceptRequestBody(
    proxyServer: SelenideProxyServer,
    endpoint: String,
    run: () -> Unit,
): String = runBlocking {
    val deferredBody = CompletableDeferred<String>()
    val filterName = UUID.randomUUID().toString()
    proxyServer.addRequestFilter(filterName) { _, httpMessageContents, httpMessageInfo ->
        val isEndpointMatch = httpMessageInfo.url.contains(endpoint)
        val isPostMethod = httpMessageInfo.originalRequest.method().name().equals("post", ignoreCase = true)
        if (isEndpointMatch && isPostMethod && deferredBody.isActive) {
            deferredBody.complete(httpMessageContents.textContents)
        }
        null // Не изменяем запрос.
    }
    try {
        run()
        withTimeout(15_000) { deferredBody.await() }
    } finally {
        proxyServer.removeRequestFilter(filterName)
    }
}

/**
 * Наблюдает первый ответ по [endpoint] во время [run] и возвращает его тело.
 * Лимит 15 секунд относится только к ожиданию тела после действия, а не к его UI-проверкам.
 * Временный фильтр снимается в finally при любом исходе, включая ошибку действия и тайм-аут.
 */
fun interceptResponseBody(
    proxyServer: SelenideProxyServer,
    endpoint: String,
    run: () -> Unit,
): String = runBlocking {
    val deferredBody = CompletableDeferred<String>()
    val filterName = UUID.randomUUID().toString()
    proxyServer.addResponseFilter(filterName) { _, httpMessageContents, httpMessageInfo ->
        val isEndpointMatch = httpMessageInfo.url.contains(endpoint)
        if (isEndpointMatch && deferredBody.isActive) {
            deferredBody.complete(httpMessageContents.textContents)
        }
    }
    try {
        run()
        withTimeout(15_000) { deferredBody.await() }
    } finally {
        proxyServer.removeResponseFilter(filterName)
    }
}

/** Подменяет тело ответов по endpoint во время действия; временный фильтр снимается также при исключении. */
fun replaceResponseBody(
    proxyServer: SelenideProxyServer,
    endpoint: String,
    responseBody: String,
    run: () -> Unit,
) {
    val filterName = UUID.randomUUID().toString()
    proxyServer.addResponseFilter(filterName) { _, httpMessageContents, httpMessageInfo ->
        val isEndpointMatch = httpMessageInfo.url.contains(endpoint)
        if (isEndpointMatch) {
            httpMessageContents.textContents = responseBody
        }
    }
    try { run() } finally { proxyServer.removeResponseFilter(filterName) }
}
