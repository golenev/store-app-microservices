package org.golenev.commondto

/** Оболочка события поставщика для тестов. Классы сервисов и их доменной модели не импортируются. */
data class DeliveryReceived(val eventId: String, val eventType: String = "DeliveryReceived", val schemaVersion: Int = 1,
                            val occurredAt: String, val storeId: String, val payload: DeliveryPayload)
