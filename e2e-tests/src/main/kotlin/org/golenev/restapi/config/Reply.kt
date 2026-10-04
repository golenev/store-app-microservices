package org.golenev.restapi.config
import com.fasterxml.jackson.module.kotlin.readValue

/** Исходный HTTP-ответ для диагностики. Вызывающий код явно выбирает DTO успешного ответа или ошибки API. */
data class Reply(val status: Int, val raw: String, val headers: Map<String, List<String>>) {
    /** Проверяет ожидаемый HTTP-статус и возвращает тот же ответ. Несовпадение останавливает проверку до разбора тела. */
    fun expect(expectedStatus: Int): Reply {
        return ResponseValidator(expectedStatus).validate(this)
    }

    /** Разбирает тело в указанный тип контракта. Отсутствующие поля не подменяются пустыми значениями. */
    inline fun <reified T> body(): T = org.golenev.utils.JsonUtils.objectMapper.readValue(raw)
}
