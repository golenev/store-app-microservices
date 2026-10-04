package org.golenev.db.tables.warehouseOutbox

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения warehouse_outbox в отдельной транзакции WAREHOUSE. */
object WarehouseOutboxDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbWarehouseExec { WarehouseOutboxTable.selectAll().where { WarehouseOutboxTable.storeId eq storeId }.count() }
    }

    /** Возвращает исходный текст сохранённого события единственной операции магазина либо null. Несколько операций требуют более точного критерия и считаются ошибкой теста. */
    fun findPayloadByStoreId(storeId: String): String? {
        return dbWarehouseExec {
            WarehouseOutboxTable.select(WarehouseOutboxTable.payload).where { WarehouseOutboxTable.storeId eq storeId }
                .map { it[WarehouseOutboxTable.payload] }.singleOrNullChecked()
        }
    }

    /** Возвращает последнюю ошибку публикации единственной операции магазина либо null. Несколько операций требуют более точного критерия и считаются ошибкой теста. */
    fun findLastErrorByStoreId(storeId: String): String? {
        return dbWarehouseExec {
            WarehouseOutboxTable.select(WarehouseOutboxTable.lastError).where { WarehouseOutboxTable.storeId eq storeId }
                .map { it[WarehouseOutboxTable.lastError] }.singleOrNullChecked()
        }
    }

    /** Возвращает состояние публикации единственной операции магазина либо null. Несколько операций требуют более точного критерия и считаются ошибкой теста. */
    fun findPublicationStatusByStoreId(storeId: String): String? {
        return dbWarehouseExec {
            WarehouseOutboxTable.select(WarehouseOutboxTable.publicationStatus).where { WarehouseOutboxTable.storeId eq storeId }
                .map { it[WarehouseOutboxTable.publicationStatus] }.singleOrNullChecked()
        }
    }

    /** Отличает отсутствие операции от неоднозначного наблюдения; произвольная первая строка не выбирается. */
    private fun <T> List<T>.singleOrNullChecked(): T? {
        check(size <= 1) { "Ожидалась одна операция магазина, найдено $size" }
        return singleOrNull()
    }
}
