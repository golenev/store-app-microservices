package org.golenev.commondto

/** Подтверждение Kafka отражает публикацию и не меняет уже зафиксированное движение товара. */
enum class PublicationStatus { PENDING, PUBLISHED }
