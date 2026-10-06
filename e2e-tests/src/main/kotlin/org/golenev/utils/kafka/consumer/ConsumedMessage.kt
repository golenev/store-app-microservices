package org.golenev.utils.kafka.consumer

/** Сообщение брокера вместе с исходным JSON и координатами для проверки маршрута и диагностики Allure. */
data class ConsumedMessage<T>(
    /** Типизированное содержимое сообщения. */
    val value: T,
    /** Ключ Kafka, задающий маршрут сообщения. */
    val key: String?,
    /** Фактический топик прочитанной записи. */
    val topic: String,
    /** Номер партиции для поиска сообщения в брокере. */
    val partition: Int,
    /** Позиция записи внутри партиции. */
    val offset: Long,
    /** Неизменённый JSON для проверки wire-контракта и вложения Allure. */
    val rawValue: String,
)
