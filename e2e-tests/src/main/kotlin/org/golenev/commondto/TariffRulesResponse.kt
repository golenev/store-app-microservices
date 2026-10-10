package org.golenev.commondto

/** Типизированный публичный список тарифов; каждая строка читается в существующую модель TariffRule. */
data class TariffRulesResponse(
    /** Сохранённые правила всех городов, возвращаемые GET /tariffs/rules. */
    val items: List<TariffRule>,
)
