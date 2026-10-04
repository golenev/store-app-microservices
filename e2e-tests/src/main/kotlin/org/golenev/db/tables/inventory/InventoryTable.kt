package org.golenev.db.tables.inventory

import org.jetbrains.exposed.sql.Table

/** Колонки inventory для явных запросов тестов; схема принадлежит приложению. */
object InventoryTable : Table("inventory") {
    val stockItemId = uuid("stock_item_id")
    val storeId = varchar("store_id", 64)
    val productId = varchar("product_id", 64)
    val availableQuantity = integer("available_quantity")
}
