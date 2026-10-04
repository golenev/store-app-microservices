package org.golenev.db.tables.stockExpenses

import org.jetbrains.exposed.sql.Table

/** Колонки stock_expenses, необходимые для наблюдения своего магазина; таблица приложения не создаётся и не изменяется через SchemaUtils. */
object StockExpensesTable : Table("stock_expenses") {
    val storeId = varchar("store_id", 64)
}
