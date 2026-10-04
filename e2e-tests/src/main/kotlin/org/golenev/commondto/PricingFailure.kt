package org.golenev.commondto

/** Ошибка приёмки или расчёта цены, доступная через WAREHOUSE. */
data class PricingFailure(val code: String, val message: String)
