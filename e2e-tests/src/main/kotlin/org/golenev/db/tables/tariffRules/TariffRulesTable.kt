package org.golenev.db.tables.tariffRules

import org.jetbrains.exposed.sql.Table

/** Существующая таблица tariff_rules приложения. Описание колонок не создаёт и не меняет схему. */
object TariffRulesTable : Table("tariff_rules") {
    val tariffRuleId = uuid("tariff_rule_id")
    val version = long("version")
    val productType = varchar("product_type", 8)
    val cityId = varchar("city_id", 64)
    val currency = varchar("currency", 3)
    val lowerBound = decimal("lower_bound", 28, 2)
    val upperBound = decimal("upper_bound", 28, 2).nullable()
    val markupRate = decimal("markup_rate", 9, 6)
}
