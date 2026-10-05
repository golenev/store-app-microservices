package org.golenev.db.tables.incomingGoodsDiagnostics

/** Сохранённые поля строки IncomingGoodsDiagnosticsTable; модель не вычисляет тестовые инварианты. */
data class IncomingGoodsDiagnosticsRow(
    /** Исходный текст сообщения, сохранённый обработчиком. */
    val rawMessage: String
)
