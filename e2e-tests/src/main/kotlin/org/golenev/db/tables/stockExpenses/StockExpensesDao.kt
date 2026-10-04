package org.golenev.db.tables.stockExpenses

import org.golenev.db.dbStoreExec
import org.golenev.db.tables.inventory.InventoryTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import java.util.*

/** Типизированные наблюдения stock_expenses в отдельной транзакции STORE. */
object StockExpensesDao {
    /** Считает движения расхода конкретного продукта; единственность расхода проверяет тест. */
    fun countByProductId(productId: String): Long {
        return dbStoreExec {
            StockExpensesTable.join(InventoryTable, JoinType.INNER, StockExpensesTable.stockItemId, InventoryTable.stockItemId)
                .selectAll().where { InventoryTable.productId eq productId }.count()
        }
    }

    /** Возвращает сумму списанного количества продукта либо null, если движений ещё нет. */
    fun sumQuantityByProductId(productId: String): Int? {
        return dbStoreExec {
            val total = StockExpensesTable.quantity.sum()
            StockExpensesTable.join(InventoryTable, JoinType.INNER, StockExpensesTable.stockItemId, InventoryTable.stockItemId)
                .select(total).where { InventoryTable.productId eq productId }.map { it[total] }.singleOrNull()
        }
    }

    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { StockExpensesTable.selectAll().where { StockExpensesTable.storeId eq storeId }.count() }
    }

    /** Возвращает сумму количества по позиции остатка либо null при отсутствии движений. */
    fun sumQuantityByStockItemId(stockItemId: UUID): Int? {
        return dbStoreExec {
            val total = StockExpensesTable.quantity.sum()
            StockExpensesTable.select(total).where { StockExpensesTable.stockItemId eq stockItemId }.map { it[total] }.singleOrNull()
        }
    }


}
