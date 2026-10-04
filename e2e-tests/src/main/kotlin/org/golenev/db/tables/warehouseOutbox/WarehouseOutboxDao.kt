package org.golenev.db.tables.warehouseOutbox

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения warehouse_outbox в отдельной транзакции WAREHOUSE. */
object WarehouseOutboxDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbWarehouseExec { WarehouseOutboxTable.selectAll().where { WarehouseOutboxTable.storeId eq storeId }.count() }
    }

    /** Возвращает исходный текст сохранённого события единственной операции магазина либо null. Отсутствие строки или несколько строк возвращают null; проверка результата выполняется в тесте. */
    fun findPayloadByStoreId(storeId: String): String? {
        return dbWarehouseExec {
            WarehouseOutboxTable.select(WarehouseOutboxTable.payload).where { WarehouseOutboxTable.storeId eq storeId }
                .map { it[WarehouseOutboxTable.payload] }.singleOrNull()
        }
    }

    /** Читает исходное событие одной поставки магазина; отсутствие возвращает null. */
    fun findPayloadByDeliveryId(storeId: String, deliveryId: String): String? {
        return dbWarehouseExec { WarehouseOutboxTable.select(WarehouseOutboxTable.payload)
            .where { (WarehouseOutboxTable.storeId eq storeId) and (WarehouseOutboxTable.deliveryId eq deliveryId) }
            .map { it[WarehouseOutboxTable.payload] }.singleOrNull() }
    }
}
