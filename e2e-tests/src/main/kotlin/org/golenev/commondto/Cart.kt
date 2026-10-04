package org.golenev.commondto

/** Корзина с версией. До принятия заявки submissionId отсутствует, после принятия — заполнен. */
data class Cart(val storeId: String, val cartId: String, val version: Long, val state: CartState,
                val items: List<CartLine>, val totalAmount: String, val currency: String,
                val submissionId: String? = null)
