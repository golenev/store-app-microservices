package org.golenev.commondto
import com.fasterxml.jackson.databind.JsonNode

/** Диагностическое состояние приёмки. Некорректные исходные данные сохраняются как JSON, без приведения к DTO корректной поставки. */
data class Receiving(val storeId: String, val deliveryId: String, val deliverySequence: Long,
                     val state: DeliveryState, val receivedAt: String, val attemptCount: Long,
                     val items: List<PricedLine>, val postedAt: String? = null,
                     val nextAttemptAt: String? = null, val lastError: PricingFailure? = null,
                     val rejectedPayload: JsonNode? = null)
