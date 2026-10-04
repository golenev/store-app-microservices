package org.golenev.db.tables.observation

import org.golenev.db.DbFactory
import org.golenev.db.DbType
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.statements.StatementType

/** SQL для составных инвариантов, очистки собственных данных и тестового DDL; подключением и транзакцией управляет Exposed. */
object ObservationDao {
    /** Выполняет составной запрос и возвращает первый столбец всех строк. Значения привязываются параметрами, ошибки не маскируются. */
    fun strings(service: String, sql: String, vararg args: Any?): List<String> {
        return DbFactory.transaction(type(service)) {
            exec(sql, args.map { VarCharColumnType() to it }, StatementType.SELECT) { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            } ?: emptyList()
        }
    }

    /** Выполняет изменение или тестовый DDL в отдельной транзакции. Разрешена только очистка/подготовка принадлежащих сценарию ресурсов. */
    fun update(service: String, sql: String, vararg args: Any?) {
        DbFactory.transaction(type(service)) {
            exec(sql, args.map { VarCharColumnType() to it }, StatementType.OTHER)
        }
    }

    /** Возвращает единственное значение либо null. Несколько строк считаются неоднозначным наблюдением и завершают тест ошибкой. */
    fun scalar(service: String, sql: String, vararg args: Any?): String? {
        val values = strings(service, sql, *args)
        check(values.size <= 1) { "Неоднозначный результат запроса к $service: $sql" }
        return values.singleOrNull()
    }

    /** Выбирает только известную роль сервиса; произвольное подключение запрещено. */
    private fun type(service: String): DbType = DbType.entries.single { it.service == service }
}
