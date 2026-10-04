package org.golenev.commondto

/** Серверная позиция корзины, также используемая в неизменяемом составе принятой заявки. */
data class CartLine(val stockItemId: String, val productId: String, val shortName: String,
                    val quantity: Int, val unitPrice: String, val lineTotal: String)
