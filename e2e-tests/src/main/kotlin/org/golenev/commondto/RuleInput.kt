package org.golenev.commondto

/** Корректные данные создания или изменения правила. Null у верхней границы означает отсутствие ограничения сверху. */
data class RuleInput(val productType: String, val cityId: String, val currency: String,
                     val lowerBound: String, val upperBound: String?, val markupRate: String)
