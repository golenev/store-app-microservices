package org.golenev.db.tables.stockMovements

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения stock_movements в отдельной транзакции STORE. */
object StockMovementsDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { StockMovementsTable.selectAll().where { StockMovementsTable.storeId eq storeId }.count() }
    }
}
