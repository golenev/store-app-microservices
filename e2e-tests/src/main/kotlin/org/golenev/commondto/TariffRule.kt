package org.golenev.commondto

/** Сохранённое тарифное правило с версией, возвращаемое при создании, чтении и изменении. */
data class TariffRule(val tariffRuleId: String, val version: Long, val productType: String,
                      val cityId: String, val currency: String, val lowerBound: String,
                      val upperBound: String?, val markupRate: String)
