package org.golenev.restapi.config

import io.qameta.allure.Allure
import org.golenev.utils.JsonUtils

/** Общая механика REST-запроса; маршруты конкретного сервиса принадлежат его DAO. */
open class RequestExecutor(baseUri: String) : BaseSpecification(baseUri) {
    /** Отправляет явно выбранный метод и неизменяемое тело; сырой текст позволяет проверять некорректный JSON. Статус вызывающий сценарий проверяет через expect. */
    fun request(path: String, method: String = "GET", body: Any? = null, key: String? = null): Reply {
        return Allure.step("$method $path", Allure.ThrowableRunnable<Reply> {
            val specification = baseRequest()
            if (body != null) specification.contentType("application/json").body(
                if (body is String) body else JsonUtils.objectMapper.writeValueAsString(body))
            if (key != null) specification.header("Idempotency-Key", key)
            val response = specification.request(method, path)
            Reply(response.statusCode, response.asString(), response.headers.groupBy({ it.name }, { it.value }))
        })
    }
}
