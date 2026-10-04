package org.golenev.commondto

/** Оболочка GoodsPosted. Идентификатор сообщения отличается от бизнес-идентификатора поставки. */
data class GoodsPosted(val eventId: String, val eventType: String, val schemaVersion: Int,
                       val occurredAt: String, val storeId: String, val payload: GoodsPayload)
