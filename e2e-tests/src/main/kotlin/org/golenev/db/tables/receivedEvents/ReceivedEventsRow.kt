package org.golenev.db.tables.receivedEvents

import java.util.UUID

/** Сохранённые поля строки ReceivedEventsTable; модель не вычисляет тестовые инварианты. */
data class ReceivedEventsRow(
    /** Магазин обработанного события. */
    val storeId: String,
    /** Поставка обработанного события. */
    val deliveryId: String,
    /** Идентификатор обработанного события. */
    val eventId: UUID
)
