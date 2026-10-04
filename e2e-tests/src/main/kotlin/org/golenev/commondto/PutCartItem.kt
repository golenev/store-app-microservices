package org.golenev.commondto

/** Запрос установки количества позиции с версией корзины, известной клиенту. */
data class PutCartItem(
    /**
     * Количество единиц товара в позиции.
     */
    val quantity: Int,
    /**
     * Версия корзины, известная клиенту; сервер проверяет её перед изменением или оформлением.
     */
    val expectedCartVersion: Long
)
