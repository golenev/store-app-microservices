package org.golenev.db.tables.processedEvents

import java.util.UUID

/** Сохранённые поля строки ProcessedEventsTable; модель не вычисляет тестовые инварианты. */
data class ProcessedEventsRow(
    /** Магазин обработанного события. */
    val storeId: String,
    /** Поставка обработанного события. */
    val deliveryId: String,
    /** Идентификатор обработанного события. */
    val eventId: UUID
)
