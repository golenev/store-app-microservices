package org.golenev.commondto

/** Неизменяемый запрос оформления. Повтор использует исходную версию корзины. */
data class SubmitCart(
    /**
     * Версия корзины, известная клиенту; сервер проверяет её перед изменением или оформлением.
     */
    val expectedCartVersion: Long
)
