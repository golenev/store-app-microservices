package org.golenev.db.tables.incomingGoodsDiagnostics

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам IncomingGoodsDiagnosticsTable; подсчёты и проверки выполняет вызывающий тест. */
object IncomingGoodsDiagnosticsDao {
    /** Читает диагностики с точным исходным текстом raw. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByRawMessage(raw: String): List<IncomingGoodsDiagnosticsRow> {
        return dbStoreExec {
            IncomingGoodsDiagnosticsTable.selectAll().where { IncomingGoodsDiagnosticsTable.rawMessage eq raw }.map {
                IncomingGoodsDiagnosticsRow(
                    rawMessage = it[IncomingGoodsDiagnosticsTable.rawMessage]
                )
            }
        }
    }

    /** Читает диагностики, содержащие eventId в исходном тексте; поиск сохраняет прежний SQL-шаблон. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByEventId(eventId: String): List<IncomingGoodsDiagnosticsRow> {
        return dbStoreExec {
            IncomingGoodsDiagnosticsTable.selectAll().where { IncomingGoodsDiagnosticsTable.rawMessage like "%$eventId%" }.map {
                IncomingGoodsDiagnosticsRow(
                    rawMessage = it[IncomingGoodsDiagnosticsTable.rawMessage]
                )
            }
        }
    }
}
