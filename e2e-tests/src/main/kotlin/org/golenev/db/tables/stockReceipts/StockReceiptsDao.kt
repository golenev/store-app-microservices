package org.golenev.db.tables.stockReceipts

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам StockReceiptsTable; подсчёты и проверки выполняет вызывающий тест. */
object StockReceiptsDao {
    /** Читает приходы поставки deliveryId в магазине storeId. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByDeliveryId(storeId: String, deliveryId: String): List<StockReceiptsRow> {
        return dbStoreExec {
            StockReceiptsTable.selectAll().where { (StockReceiptsTable.storeId eq storeId) and (StockReceiptsTable.deliveryId eq deliveryId) }.map {
                StockReceiptsRow(
                    storeId = it[StockReceiptsTable.storeId],
                    deliveryId = it[StockReceiptsTable.deliveryId]
                )
            }
        }
    }
}
