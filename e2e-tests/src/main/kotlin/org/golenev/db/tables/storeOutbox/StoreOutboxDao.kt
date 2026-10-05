package org.golenev.db.tables.storeOutbox

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам StoreOutboxTable; подсчёты и проверки выполняет вызывающий тест. */
object StoreOutboxDao {
    /** Читает исходящие события магазина storeId без выбора единственной записи. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStoreId(storeId: String): List<StoreOutboxRow> {
        return dbStoreExec {
            StoreOutboxTable.selectAll().where { StoreOutboxTable.storeId eq storeId }.map {
                StoreOutboxRow(
                    storeId = it[StoreOutboxTable.storeId],
                    payload = it[StoreOutboxTable.payload],
                    lastError = it[StoreOutboxTable.lastError],
                    publicationStatus = it[StoreOutboxTable.publicationStatus],
                    submissionId = it[StoreOutboxTable.submissionId],
                    eventId = it[StoreOutboxTable.eventId]
                )
            }
        }
    }
}
