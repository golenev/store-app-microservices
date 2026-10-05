package org.golenev.db.tables.submissions

import java.util.UUID

/** Сохранённые поля строки SubmissionsTable; модель не вычисляет тестовые инварианты. */
data class SubmissionsRow(
    /** Магазин принятой заявки. */
    val storeId: String,
    /** Идентификатор принятой заявки. */
    val submissionId: UUID,
    /** Закрытая корзина заявки. */
    val cartId: UUID
)
