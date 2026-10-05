package org.golenev.db.tables.warehouseOutbox

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам WarehouseOutboxTable; подсчёты и проверки выполняет вызывающий тест. */
object WarehouseOutboxDao {
    /** Читает исходящие события магазина storeId без выбора единственной записи. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStoreId(storeId: String): List<WarehouseOutboxRow> {
        return dbWarehouseExec {
            WarehouseOutboxTable.selectAll().where { WarehouseOutboxTable.storeId eq storeId }.map {
                WarehouseOutboxRow(
                    storeId = it[WarehouseOutboxTable.storeId],
                    payload = it[WarehouseOutboxTable.payload],
                    lastError = it[WarehouseOutboxTable.lastError],
                    publicationStatus = it[WarehouseOutboxTable.publicationStatus],
                    deliveryId = it[WarehouseOutboxTable.deliveryId]
                )
            }
        }
    }

    /** Читает исходящие события поставки deliveryId магазина storeId. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByDeliveryId(storeId: String, deliveryId: String): List<WarehouseOutboxRow> {
        return dbWarehouseExec {
            WarehouseOutboxTable.selectAll().where { (WarehouseOutboxTable.storeId eq storeId) and (WarehouseOutboxTable.deliveryId eq deliveryId) }.map {
                WarehouseOutboxRow(
                    storeId = it[WarehouseOutboxTable.storeId],
                    payload = it[WarehouseOutboxTable.payload],
                    lastError = it[WarehouseOutboxTable.lastError],
                    publicationStatus = it[WarehouseOutboxTable.publicationStatus],
                    deliveryId = it[WarehouseOutboxTable.deliveryId]
                )
            }
        }
    }
}
