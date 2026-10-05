package org.golenev.db.tables.processedEvents

import java.util.UUID
import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам ProcessedEventsTable; подсчёты и проверки выполняет вызывающий тест. */
object ProcessedEventsDao {
    /** Читает события по eventId; некорректный UUID передаёт ошибку вызывающему тесту. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByEventId(eventId: String): List<ProcessedEventsRow> {
        return dbStoreExec {
            ProcessedEventsTable.selectAll().where { ProcessedEventsTable.eventId eq UUID.fromString(eventId) }.map {
                ProcessedEventsRow(
                    storeId = it[ProcessedEventsTable.storeId],
                    deliveryId = it[ProcessedEventsTable.deliveryId],
                    eventId = it[ProcessedEventsTable.eventId]
                )
            }
        }
    }
}
