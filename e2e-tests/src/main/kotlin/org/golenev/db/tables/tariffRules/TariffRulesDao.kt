package org.golenev.db.tables.tariffRules

import io.qameta.allure.Step
import org.golenev.db.dbTariffsExec
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Прямые операции с тарифами в развёрнутой TARIFFS. Проверки результата выполняются в тестах. */
object TariffRulesDao {
    /** Фиксирует одну подготовленную строку в отдельной транзакции; нарушение ограничений БД передаётся тесту. */
    @Step("INSERT tariff_rules: сохраняем подготовленную строку {row}")
    fun insert(row: TariffRuleRow) {
        dbTariffsExec {
            TariffRulesTable.insert {
                it[tariffRuleId] = row.tariffRuleId
                it[version] = row.version
                it[productType] = row.productType
                it[cityId] = row.cityId
                it[currency] = row.currency
                it[lowerBound] = row.lowerBound
                it[upperBound] = row.upperBound
                it[markupRate] = row.markupRate
            }
        }
    }

    /** Читает строку по идентификатору в новой транзакции после фиксации HTTP-операции; отсутствие возвращает пустой список. */
    @Step("SELECT tariff_rules: читаем идентификатор {ruleId}")
    fun findById(ruleId: UUID): List<TariffRuleRow> = dbTariffsExec {
        TariffRulesTable.selectAll().where { TariffRulesTable.tariffRuleId eq ruleId }.map { map(it) }
    }

    /** Читает только строки своего города без ограничения общим каталогом; порядок задаётся идентификатором. */
    @Step("SELECT tariff_rules: читаем город {cityId}")
    fun findByCity(cityId: String): List<TariffRuleRow> = dbTariffsExec {
        TariffRulesTable.selectAll().where { TariffRulesTable.cityId eq cityId }
            .orderBy(TariffRulesTable.tariffRuleId).map { map(it) }
    }

    /** Заменяет поля и явно заданную версию одной записи; возвращает число изменённых строк после фиксации транзакции. */
    @Step("UPDATE tariff_rules: сохраняем новые поля {row}")
    fun replace(row: TariffRuleRow): Int = dbTariffsExec {
        TariffRulesTable.update({ TariffRulesTable.tariffRuleId eq row.tariffRuleId }) {
            it[version] = row.version
            it[productType] = row.productType
            it[cityId] = row.cityId
            it[currency] = row.currency
            it[lowerBound] = row.lowerBound
            it[upperBound] = row.upperBound
            it[markupRate] = row.markupRate
        }
    }

    /** Удаляет один идентификатор и фиксирует транзакцию до последующего GET; возвращает число удалённых строк. */
    @Step("DELETE tariff_rules: удаляем идентификатор {ruleId}")
    fun deleteById(ruleId: UUID): Int = dbTariffsExec {
        TariffRulesTable.deleteWhere { tariffRuleId eq ruleId }
    }

    /** Идемпотентно удаляет только строки уникального города текущего теста, в том числе после падения HTTP-проверки. */
    @Step("DELETE tariff_rules: очищаем город текущего теста {cityId}")
    fun deleteByCity(cityId: String): Int = dbTariffsExec {
        TariffRulesTable.deleteWhere { TariffRulesTable.cityId eq cityId }
    }

    /** Переносит все восемь колонок в типизированный снимок без форматирования и проверки бизнес-полей. */
    private fun map(row: ResultRow): TariffRuleRow = TariffRuleRow(
        tariffRuleId = row[TariffRulesTable.tariffRuleId],
        version = row[TariffRulesTable.version],
        productType = row[TariffRulesTable.productType],
        cityId = row[TariffRulesTable.cityId],
        currency = row[TariffRulesTable.currency],
        lowerBound = row[TariffRulesTable.lowerBound],
        upperBound = row[TariffRulesTable.upperBound],
        markupRate = row[TariffRulesTable.markupRate],
    )
}
