package org.golenev.db.tables.stockExpenses

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения stock_expenses в отдельной транзакции STORE. */
object StockExpensesDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { StockExpensesTable.selectAll().where { StockExpensesTable.storeId eq storeId }.count() }
    }
}
