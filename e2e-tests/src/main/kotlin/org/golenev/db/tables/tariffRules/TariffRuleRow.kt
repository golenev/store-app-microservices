package org.golenev.db.tables.tariffRules

import java.math.BigDecimal
import java.util.UUID

/**
 * Полный снимок строки tariff_rules для независимой проверки API и подготовки данных.
 * Цены имеют точность два знака, ставка — шесть; отсутствие верхней границы хранится как null.
 * @property tariffRuleId идентификатор записи
 * @property version версия сохранённых условий
 * @property productType тип товаров
 * @property cityId город действия условий
 * @property currency валюта закупочной цены
 * @property lowerBound включённая нижняя граница
 * @property upperBound исключённая верхняя граница либо отсутствие предела
 * @property markupRate дробная наценка: 0.20 означает 20 процентов
 */
data class TariffRuleRow(
    val tariffRuleId: UUID,
    val version: Long,
    val productType: String,
    val cityId: String,
    val currency: String,
    val lowerBound: BigDecimal,
    val upperBound: BigDecimal?,
    val markupRate: BigDecimal,
)
