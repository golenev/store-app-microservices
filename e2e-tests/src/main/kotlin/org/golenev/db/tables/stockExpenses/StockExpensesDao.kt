package org.golenev.db.tables.stockExpenses

import java.util.UUID
import org.golenev.db.dbStoreExec
import org.golenev.db.tables.inventory.InventoryTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам StockExpensesTable; подсчёты и проверки выполняет вызывающий тест. */
object StockExpensesDao {
    /** Читает расходы продукта productId через связь с inventory. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByProductId(productId: String): List<StockExpensesRow> {
        return dbStoreExec {
            StockExpensesTable.join(
                InventoryTable, JoinType.INNER, StockExpensesTable.stockItemId, InventoryTable.stockItemId
            ).selectAll().where { InventoryTable.productId eq productId }.map {
                StockExpensesRow(
                    storeId = it[StockExpensesTable.storeId],
                    stockItemId = it[StockExpensesTable.stockItemId],
                    quantity = it[StockExpensesTable.quantity],
                    submissionId = it[StockExpensesTable.submissionId]
                )
            }
        }
    }

    /** Читает расходы магазина storeId. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStoreId(storeId: String): List<StockExpensesRow> {
        return dbStoreExec {
            StockExpensesTable.selectAll().where { StockExpensesTable.storeId eq storeId }.map {
                StockExpensesRow(
                    storeId = it[StockExpensesTable.storeId],
                    stockItemId = it[StockExpensesTable.stockItemId],
                    quantity = it[StockExpensesTable.quantity],
                    submissionId = it[StockExpensesTable.submissionId]
                )
            }
        }
    }

    /** Читает расходы позиции stockItemId без суммирования количества. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStockItemId(stockItemId: UUID): List<StockExpensesRow> {
        return dbStoreExec {
            StockExpensesTable.selectAll().where { StockExpensesTable.stockItemId eq stockItemId }.map {
                StockExpensesRow(
                    storeId = it[StockExpensesTable.storeId],
                    stockItemId = it[StockExpensesTable.stockItemId],
                    quantity = it[StockExpensesTable.quantity],
                    submissionId = it[StockExpensesTable.submissionId]
                )
            }
        }
    }
}
