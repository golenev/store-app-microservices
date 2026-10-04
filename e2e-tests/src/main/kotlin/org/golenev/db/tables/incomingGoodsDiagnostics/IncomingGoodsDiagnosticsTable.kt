package org.golenev.db.tables.incomingGoodsDiagnostics

import org.jetbrains.exposed.sql.Table

/** Сохранённые исходные сообщения, отклонённые обработчиком; схема принадлежит приложению. */
object IncomingGoodsDiagnosticsTable : Table("incoming_goods_diagnostics") {
    val rawMessage = text("raw_message")
}
