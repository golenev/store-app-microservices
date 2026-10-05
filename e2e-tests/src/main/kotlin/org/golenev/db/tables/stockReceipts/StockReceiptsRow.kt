package org.golenev.db.tables.stockReceipts

/** Сохранённые поля строки StockReceiptsTable; модель не вычисляет тестовые инварианты. */
data class StockReceiptsRow(
    /** Магазин оприходования. */
    val storeId: String,
    /** Идентификатор принятой поставки. */
    val deliveryId: String
)
