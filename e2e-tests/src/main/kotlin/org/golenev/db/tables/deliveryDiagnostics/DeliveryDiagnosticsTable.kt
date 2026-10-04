package org.golenev.db.tables.deliveryDiagnostics

import org.jetbrains.exposed.sql.Table

/** Сохранённые исходные сообщения, отклонённые обработчиком; схема принадлежит приложению. */
object DeliveryDiagnosticsTable : Table("delivery_diagnostics") {
    val rawMessage = text("raw_message")
}
