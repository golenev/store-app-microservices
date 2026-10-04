package org.golenev.db.tables.stockReceipts

import org.jetbrains.exposed.sql.Table

/** Колонки stock_receipts для явных запросов тестов; схема принадлежит приложению. */
object StockReceiptsTable : Table("stock_receipts") {
    val storeId = varchar("store_id", 64)
    val deliveryId = varchar("delivery_id", 64)
}
