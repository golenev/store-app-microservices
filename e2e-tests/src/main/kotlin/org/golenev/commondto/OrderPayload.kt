package org.golenev.commondto

/** Сохранённый состав принятой заявки. Будущие изменения цен каталога не пересчитывают его значения. */
data class OrderPayload(val submissionId: String, val cartId: String, val acceptedAt: String,
                        val items: List<CartLine>, val totalAmount: String, val currency: String)
