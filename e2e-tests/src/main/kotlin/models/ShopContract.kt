package models

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/** Closed v1 cart states; an unexpected server state is a parsing failure, not an empty cart. */
enum class CartState { OPEN, SUBMITTED }
/** Persisted receiving states exposed by the v1 diagnostic API. */
enum class DeliveryState { WAITING_PRICING, POSTED, REJECTED }
/** Broker acknowledgement is independent of the already committed stock movement. */
enum class PublicationStatus { PENDING, PUBLISHED }

/** Exact STORE catalog position; money stays in its wire decimal-string representation. */
data class Stock(val stockItemId: String, val productId: String, val productType: String,
                 val shortName: String, val description: String, val unitPrice: String,
                 val currency: String, val availableQuantity: Int)
/** A scoped collection; item order is not part of the contract. */
data class Catalog(val storeId: String, val items: List<Stock>)
/** Server-owned cart line used both by the cart response and the accepted order snapshot. */
data class CartLine(val stockItemId: String, val productId: String, val shortName: String,
                    val quantity: Int, val unitPrice: String, val lineTotal: String)
/** Versioned cart; submissionId is absent before acceptance and present afterwards. */
data class Cart(val storeId: String, val cartId: String, val version: Long, val state: CartState,
                val items: List<CartLine>, val totalAmount: String, val currency: String,
                val submissionId: String? = null)
/** Absolute cart quantity with the caller's captured version. */
data class PutCartItem(val quantity: Int, val expectedCartVersion: Long)
/** Immutable acceptance request: a replay keeps this original version. */
data class SubmitCart(val expectedCartVersion: Long)
/** Persisted technical operation; publishedAt remains absent while acknowledgement is pending. */
data class Submission(val storeId: String, val submissionId: String, val cartId: String,
                      val eventId: String, val publicationStatus: PublicationStatus,
                      val acceptedAt: String, val publishedAt: String? = null)
/** Contractual HTTP error; diagnostic text is evidence rather than an invented universal matcher. */
data class ApiError(val timestamp: String, val status: Int, val code: String, val message: String, val path: String,
                    val details: List<ErrorDetail>? = null)
/** Safe field validation detail in the TARIFFS error contract; rejected input values are intentionally absent. */
data class ErrorDetail(val field: String, val message: String)
/** Receiving/pricing failure exposed by WAREHOUSE. */
data class PricingFailure(val code: String, val message: String)
/** Receiving line; pricing fields are genuinely absent until pricing succeeds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PricedLine(val lineId: String, val productId: String, val productType: String,
                      val shortName: String, val description: String, val quantity: Int,
                      val purchasePrice: String, val currency: String, val markupRate: String? = null,
                      val tariffRuleId: String? = null, val tariffVersion: Long? = null, val salePrice: String? = null)
/** Diagnostic receiving view; invalid payload is deliberately raw rather than coerced into a valid DTO. */
data class Receiving(val storeId: String, val deliveryId: String, val deliverySequence: Long,
                     val state: DeliveryState, val receivedAt: String, val attemptCount: Long,
                     val items: List<PricedLine>, val postedAt: String? = null,
                     val nextAttemptAt: String? = null, val lastError: PricingFailure? = null,
                     val rejectedPayload: JsonNode? = null)
/** Priced immutable delivery snapshot published by WAREHOUSE. */
data class GoodsPayload(val deliveryId: String, val deliverySequence: Long, val receivedAt: String,
                        val postedAt: String, val items: List<PricedLine>)
/** GoodsPosted envelope; transport identity and business delivery identity remain distinct. */
data class GoodsPosted(val eventId: String, val eventType: String, val schemaVersion: Int,
                       val occurredAt: String, val storeId: String, val payload: GoodsPayload)
/** Accepted order snapshot; its values must not be recalculated from future catalog prices. */
data class OrderPayload(val submissionId: String, val cartId: String, val acceptedAt: String,
                        val items: List<CartLine>, val totalAmount: String, val currency: String)
/** Output event observed independently of the HTTP acceptance response. */
data class OrderSubmitted(val eventId: String, val eventType: String, val schemaVersion: Int,
                          val occurredAt: String, val storeId: String, val payload: OrderPayload)
/** Valid tariff CRUD input, preserving explicit null for the unbounded upper interval. */
data class RuleInput(val productType: String, val cityId: String, val currency: String,
                     val lowerBound: String, val upperBound: String?, val markupRate: String)
/** Versioned persisted tariff rule returned by create/read/update. */
data class TariffRule(val tariffRuleId: String, val version: Long, val productType: String,
                      val cityId: String, val currency: String, val lowerBound: String,
                      val upperBound: String?, val markupRate: String)
/** Fractional quote and the exact rule version used for pricing. */
data class Quote(val markupRate: String, val tariffRuleId: String, val tariffVersion: Long)
