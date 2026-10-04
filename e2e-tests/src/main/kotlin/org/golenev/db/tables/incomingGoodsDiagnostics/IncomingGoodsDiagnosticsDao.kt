package org.golenev.db.tables.incomingGoodsDiagnostics

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированное наблюдение диагностик STORE без изменения исходного сообщения. */
object IncomingGoodsDiagnosticsDao {
    /** Считает диагностики с точным исходным текстом; ошибка разбора сообщения не подменяется успешным DTO. */
    fun countByRawMessage(raw: String): Long {
        return dbStoreExec { IncomingGoodsDiagnosticsTable.selectAll().where { IncomingGoodsDiagnosticsTable.rawMessage eq raw }.count() }
    }

    /** Считает диагностики с заданным идентификатором события в исходном тексте; шаблон нужен для конфликта изменённой оболочки. */
    fun countByEventId(eventId: String): Long {
        return dbStoreExec { IncomingGoodsDiagnosticsTable.selectAll().where { IncomingGoodsDiagnosticsTable.rawMessage like "%$eventId%" }.count() }
    }
}
