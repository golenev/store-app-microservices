package org.golenev.db.tables.submissions

import org.golenev.db.dbStoreExec
import org.golenev.db.tables.storeOutbox.StoreOutboxTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.util.*

/** Типизированные наблюдения submissions в отдельной транзакции STORE. */
object SubmissionsDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { SubmissionsTable.selectAll().where { SubmissionsTable.storeId eq storeId }.count() }
    }

    /** Возвращает идентификаторы принятых операций без outbox; отсутствие ошибок проверяет тест. */
    fun findIdsWithoutOutboxByStoreId(storeId: String): List<UUID> {
        return dbStoreExec {
            SubmissionsTable.join(StoreOutboxTable, JoinType.LEFT, SubmissionsTable.submissionId, StoreOutboxTable.submissionId)
                .select(SubmissionsTable.submissionId)
                .where { (SubmissionsTable.storeId eq storeId) and StoreOutboxTable.eventId.isNull() }
                .map { it[SubmissionsTable.submissionId] }
        }
    }
}
