package org.golenev.db.tables.stockMovements

import java.util.UUID

/** Сохранённые поля строки StockMovementsTable; модель не вычисляет тестовые инварианты. */
data class StockMovementsRow(
    /** Магазин движения. */
    val storeId: String,
    /** Позиция остатка, к которой относится движение. */
    val stockItemId: UUID,
    /** Сохранённое количество единиц движения. */
    val quantity: Int
)
