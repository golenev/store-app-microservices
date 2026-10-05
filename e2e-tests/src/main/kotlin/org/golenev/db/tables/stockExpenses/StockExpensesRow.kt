package org.golenev.db.tables.stockExpenses

import java.util.UUID

/** Сохранённые поля строки StockExpensesTable; модель не вычисляет тестовые инварианты. */
data class StockExpensesRow(
    /** Магазин движения. */
    val storeId: String,
    /** Позиция остатка, к которой относится движение. */
    val stockItemId: UUID,
    /** Сохранённое количество единиц движения. */
    val quantity: Int,
    /** Принятая заявка, создавшая расход. */
    val submissionId: UUID
)
