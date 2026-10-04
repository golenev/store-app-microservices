package org.golenev.commondto

/** Подтверждение Kafka отражает публикацию и не меняет уже зафиксированное движение товара. */
enum class PublicationStatus {
    /**
     * Операция принята, но публикация её события в Kafka ещё не подтверждена.
     */
    PENDING,
    /**
     * Публикация события операции в Kafka подтверждена.
     */
    PUBLISHED
}
