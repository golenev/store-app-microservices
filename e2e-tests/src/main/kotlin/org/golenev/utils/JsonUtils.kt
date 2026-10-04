package org.golenev.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Единственный mapper для HTTP, Kafka и тестов: поддерживает Kotlin и даты Java Time. Настройки задаются один раз при инициализации. */
object JsonUtils {
    val objectMapper: ObjectMapper by lazy {
        jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
