package org.golenev.db.tables.stockMovements

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import java.util.*

/** Типизированные наблюдения stock_movements в отдельной транзакции STORE. */
object StockMovementsDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { StockMovementsTable.selectAll().where { StockMovementsTable.storeId eq storeId }.count() }
    }

    /** Возвращает сумму количества по позиции остатка либо null при отсутствии движений. */
    fun sumQuantityByStockItemId(stockItemId: UUID): Int? {
        return dbStoreExec {
            val total = StockMovementsTable.quantity.sum()
            StockMovementsTable.select(total).where { StockMovementsTable.stockItemId eq stockItemId }.map { it[total] }.singleOrNull()
        }
    }


}
