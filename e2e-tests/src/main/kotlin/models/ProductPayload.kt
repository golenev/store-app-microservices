package models

/** Supplier line contract v1; money is a decimal string, not a binary floating-point number or caller-owned sale price. */
data class DeliveryLine(val lineId: String, val productId: String, val productType: String, val shortName: String,
                        val description: String, val quantity: Int, val purchasePrice: String, val currency: String = "RUB")
/** Immutable business payload; a retry preserves deliveryId and every original line. */
data class DeliveryPayload(val deliveryId: String, val items: List<DeliveryLine>)
/** Supplier envelope shared only by tests; backend service/domain classes are not imported. */
data class DeliveryReceived(val eventId: String, val eventType: String = "DeliveryReceived", val schemaVersion: Int = 1,
                            val occurredAt: String, val storeId: String, val payload: DeliveryPayload)
