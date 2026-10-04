package org.golenev.commondto

/** Коэффициент наценки и точная версия правила, использованная при расчёте. */
data class Quote(val markupRate: String, val tariffRuleId: String, val tariffVersion: Long)
