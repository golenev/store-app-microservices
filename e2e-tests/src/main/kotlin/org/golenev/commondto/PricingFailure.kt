package org.golenev.commondto

/** Ошибка приёмки или расчёта цены, доступная через WAREHOUSE. */
data class PricingFailure(
    /**
     * Машинный код ошибки для явной проверки в тесте.
     */
    val code: String,
    /**
     * Диагностическое описание причины ошибки.
     */
    val message: String
)
