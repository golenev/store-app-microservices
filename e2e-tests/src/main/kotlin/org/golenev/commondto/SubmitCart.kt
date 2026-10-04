package org.golenev.commondto

/** Неизменяемый запрос оформления. Повтор использует исходную версию корзины. */
data class SubmitCart(val expectedCartVersion: Long)
