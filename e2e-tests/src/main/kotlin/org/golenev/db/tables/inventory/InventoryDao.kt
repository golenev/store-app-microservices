package org.golenev.db.tables.inventory

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Явные запросы Inventory через Exposed; DAO возвращает данные без проверок тестовых инвариантов. */
object InventoryDao {


    /** Читает все позиции по storeId из одного снимка STORE; пустой результат возвращается списком. */
    fun findByStoreId(storeId: String): List<InventoryRow> {
        return dbStoreExec {
            InventoryTable.selectAll().where { InventoryTable.storeId eq storeId }.map {
                InventoryRow(it[InventoryTable.stockItemId], it[InventoryTable.storeId], it[InventoryTable.productId], it[InventoryTable.availableQuantity])
            }
        }
    }

    /** Читает все позиции по productId из одного снимка STORE; пустой результат возвращается списком. */
    fun findByProductId(productId: String): List<InventoryRow> {
        return dbStoreExec {
            InventoryTable.selectAll().where { InventoryTable.productId eq productId }.map {
                InventoryRow(it[InventoryTable.stockItemId], it[InventoryTable.storeId], it[InventoryTable.productId], it[InventoryTable.availableQuantity])
            }
        }
    }


}
