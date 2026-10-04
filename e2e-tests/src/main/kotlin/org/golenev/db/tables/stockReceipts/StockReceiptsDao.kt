package org.golenev.db.tables.stockReceipts

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** Явные запросы StockReceipts через Exposed; DAO возвращает данные без проверок тестовых инвариантов. */
object StockReceiptsDao {

    /** Считает приходы одной поставки магазина; проверка единственности остаётся в тесте. */
    fun countByDeliveryId(storeId: String, deliveryId: String): Long {
        return dbStoreExec { StockReceiptsTable.selectAll().where { (StockReceiptsTable.storeId eq storeId) and (StockReceiptsTable.deliveryId eq deliveryId) }.count() }
    }
}
