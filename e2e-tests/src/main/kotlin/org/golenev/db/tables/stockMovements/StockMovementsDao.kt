package org.golenev.db.tables.stockMovements

import java.util.UUID
import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам StockMovementsTable; подсчёты и проверки выполняет вызывающий тест. */
object StockMovementsDao {
    /** Читает приходные движения магазина storeId. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStoreId(storeId: String): List<StockMovementsRow> {
        return dbStoreExec {
            StockMovementsTable.selectAll().where { StockMovementsTable.storeId eq storeId }.map {
                StockMovementsRow(
                    storeId = it[StockMovementsTable.storeId],
                    stockItemId = it[StockMovementsTable.stockItemId],
                    quantity = it[StockMovementsTable.quantity]
                )
            }
        }
    }

    /** Читает приходные движения позиции stockItemId без суммирования количества. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStockItemId(stockItemId: UUID): List<StockMovementsRow> {
        return dbStoreExec {
            StockMovementsTable.selectAll().where { StockMovementsTable.stockItemId eq stockItemId }.map {
                StockMovementsRow(
                    storeId = it[StockMovementsTable.storeId],
                    stockItemId = it[StockMovementsTable.stockItemId],
                    quantity = it[StockMovementsTable.quantity]
                )
            }
        }
    }
}
