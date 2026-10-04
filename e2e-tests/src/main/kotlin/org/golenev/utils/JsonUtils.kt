package org.golenev.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Общие настройки JSON для HTTP и Kafka. Неизвестные поля и ошибки обязательных полей не скрываются. */
object JsonUtils {
    val objectMapper = jacksonObjectMapper()
}
