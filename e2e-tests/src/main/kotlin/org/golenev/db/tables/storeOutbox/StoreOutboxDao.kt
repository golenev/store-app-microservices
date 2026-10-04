package org.golenev.db.tables.storeOutbox

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения store_outbox в отдельной транзакции STORE. */
object StoreOutboxDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { StoreOutboxTable.selectAll().where { StoreOutboxTable.storeId eq storeId }.count() }
    }

    /** Возвращает исходный текст сохранённого события единственной операции магазина либо null. Несколько операций требуют более точного критерия и считаются ошибкой теста. */
    fun findPayloadByStoreId(storeId: String): String? {
        return dbStoreExec {
            StoreOutboxTable.select(StoreOutboxTable.payload).where { StoreOutboxTable.storeId eq storeId }
                .map { it[StoreOutboxTable.payload] }.singleOrNullChecked()
        }
    }

    /** Возвращает последнюю ошибку публикации единственной операции магазина либо null. Несколько операций требуют более точного критерия и считаются ошибкой теста. */
    fun findLastErrorByStoreId(storeId: String): String? {
        return dbStoreExec {
            StoreOutboxTable.select(StoreOutboxTable.lastError).where { StoreOutboxTable.storeId eq storeId }
                .map { it[StoreOutboxTable.lastError] }.singleOrNullChecked()
        }
    }

    /** Возвращает состояние публикации единственной операции магазина либо null. Несколько операций требуют более точного критерия и считаются ошибкой теста. */
    fun findPublicationStatusByStoreId(storeId: String): String? {
        return dbStoreExec {
            StoreOutboxTable.select(StoreOutboxTable.publicationStatus).where { StoreOutboxTable.storeId eq storeId }
                .map { it[StoreOutboxTable.publicationStatus] }.singleOrNullChecked()
        }
    }

    /** Отличает отсутствие операции от неоднозначного наблюдения; произвольная первая строка не выбирается. */
    private fun <T> List<T>.singleOrNullChecked(): T? {
        check(size <= 1) { "Ожидалась одна операция магазина, найдено $size" }
        return singleOrNull()
    }
}
