package org.golenev.utils
import org.golenev.utils.JsonUtils
import org.golenev.restapi.endpoints.*
import com.fasterxml.jackson.module.kotlin.readValue

/** Физическая запись Kafka: ключ, идентификатор события, раздел, смещение и исходный текст хранятся отдельно от DTO данных. */
data class ObservedEvent(val key: String, val eventId: String, val partition: Int, val offset: Long, val raw: String) {
    /** Разбирает запись в указанный тип контракта. Ошибка разбора целевого события не считается его отсутствием. */
    inline fun <reified T> body(): T = JsonUtils.objectMapper.readValue(raw)
}
