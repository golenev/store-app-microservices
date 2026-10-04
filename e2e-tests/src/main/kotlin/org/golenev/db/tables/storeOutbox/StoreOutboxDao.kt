package org.golenev.db.tables.storeOutbox

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения store_outbox в отдельной транзакции STORE. */
object StoreOutboxDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { StoreOutboxTable.selectAll().where { StoreOutboxTable.storeId eq storeId }.count() }
    }

    /** Возвращает исходный текст сохранённого события единственной операции магазина либо null. Отсутствие строки или несколько строк возвращают null; проверка результата выполняется в тесте. */
    fun findPayloadByStoreId(storeId: String): String? {
        return dbStoreExec {
            StoreOutboxTable.select(StoreOutboxTable.payload).where { StoreOutboxTable.storeId eq storeId }
                .map { it[StoreOutboxTable.payload] }.singleOrNull()
        }
    }


}
