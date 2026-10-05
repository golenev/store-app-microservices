package org.golenev.db.tables.deliveryDiagnostics

/** Сохранённые поля строки DeliveryDiagnosticsTable; модель не вычисляет тестовые инварианты. */
data class DeliveryDiagnosticsRow(
    /** Исходный текст сообщения, сохранённый обработчиком. */
    val rawMessage: String
)
