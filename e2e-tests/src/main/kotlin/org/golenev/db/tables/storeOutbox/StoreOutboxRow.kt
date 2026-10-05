package org.golenev.db.tables.storeOutbox

import java.util.UUID

/** Сохранённые поля строки StoreOutboxTable; модель не вычисляет тестовые инварианты. */
data class StoreOutboxRow(
    /** Магазин исходящего события. */
    val storeId: String,
    /** Неизменяемый исходный JSON события. */
    val payload: String,
    /** Последняя сохранённая ошибка публикации, если есть. */
    val lastError: String?,
    /** Сохранённый статус публикации. */
    val publicationStatus: String,
    /** Принятая заявка события. */
    val submissionId: UUID,
    /** Постоянный идентификатор события. */
    val eventId: UUID
)
