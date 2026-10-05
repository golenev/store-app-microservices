package org.golenev.db.tables.submissions

import java.util.UUID
import org.golenev.db.dbStoreExec
import org.golenev.db.tables.storeOutbox.StoreOutboxTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам SubmissionsTable; подсчёты и проверки выполняет вызывающий тест. */
object SubmissionsDao {
    /** Читает принятые заявки магазина storeId. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByStoreId(storeId: String): List<SubmissionsRow> {
        return dbStoreExec {
            SubmissionsTable.selectAll().where { SubmissionsTable.storeId eq storeId }.map {
                SubmissionsRow(
                    storeId = it[SubmissionsTable.storeId],
                    submissionId = it[SubmissionsTable.submissionId],
                    cartId = it[SubmissionsTable.cartId]
                )
            }
        }
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
