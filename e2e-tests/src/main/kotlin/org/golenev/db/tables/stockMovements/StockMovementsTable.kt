package org.golenev.db.tables.stockMovements

import org.jetbrains.exposed.sql.Table

/** Колонки stock_movements, необходимые для наблюдения своего магазина; таблица приложения не создаётся и не изменяется через SchemaUtils. */
object StockMovementsTable : Table("stock_movements") {
    val storeId = varchar("store_id", 64)
    val stockItemId = uuid("stock_item_id")
    val quantity = integer("quantity")
}
