package org.golenev.commondto

/** Неизменяемый результат расчёта поставки, публикуемый WAREHOUSE. */
data class GoodsPayload(val deliveryId: String, val deliverySequence: Long, val receivedAt: String,
                        val postedAt: String, val items: List<PricedLine>)
