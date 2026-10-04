package org.golenev.db.tables.deliveryDiagnostics

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированное наблюдение диагностик WAREHOUSE без изменения исходного сообщения. */
object DeliveryDiagnosticsDao {
    /** Считает диагностики с точным исходным текстом; ошибка разбора сообщения не подменяется успешным DTO. */
    fun countByRawMessage(raw: String): Long {
        return dbWarehouseExec { DeliveryDiagnosticsTable.selectAll().where { DeliveryDiagnosticsTable.rawMessage eq raw }.count() }
    }

    /** Считает диагностики с заданным идентификатором события в исходном тексте; шаблон нужен для конфликта изменённой оболочки. */
    fun countByEventId(eventId: String): Long {
        return dbWarehouseExec { DeliveryDiagnosticsTable.selectAll().where { DeliveryDiagnosticsTable.rawMessage like "%$eventId%" }.count() }
    }
}
