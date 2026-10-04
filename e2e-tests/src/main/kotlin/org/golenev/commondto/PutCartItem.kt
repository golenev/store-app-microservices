package org.golenev.commondto

/** Запрос установки количества позиции с версией корзины, известной клиенту. */
data class PutCartItem(val quantity: Int, val expectedCartVersion: Long)
