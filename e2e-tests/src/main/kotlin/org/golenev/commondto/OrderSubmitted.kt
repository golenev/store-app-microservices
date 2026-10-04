package org.golenev.commondto

/** Исходящее событие заявки, наблюдаемое независимо от HTTP-ответа оформления. */
data class OrderSubmitted(val eventId: String, val eventType: String, val schemaVersion: Int,
                          val occurredAt: String, val storeId: String, val payload: OrderPayload)
