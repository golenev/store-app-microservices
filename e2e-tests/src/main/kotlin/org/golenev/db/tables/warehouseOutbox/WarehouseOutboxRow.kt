package org.golenev.db.tables.warehouseOutbox

/** Сохранённые поля строки WarehouseOutboxTable; модель не вычисляет тестовые инварианты. */
data class WarehouseOutboxRow(
    /** Магазин исходящего события. */
    val storeId: String,
    /** Неизменяемый исходный JSON события. */
    val payload: String,
    /** Последняя сохранённая ошибка публикации, если есть. */
    val lastError: String?,
    /** Сохранённый статус публикации. */
    val publicationStatus: String,
    /** Поставка исходящего события. */
    val deliveryId: String
)
