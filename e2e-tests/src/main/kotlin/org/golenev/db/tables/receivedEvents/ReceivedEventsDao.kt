package org.golenev.db.tables.receivedEvents

import java.util.UUID
import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам ReceivedEventsTable; подсчёты и проверки выполняет вызывающий тест. */
object ReceivedEventsDao {
    /** Читает события по eventId; некорректный UUID передаёт ошибку вызывающему тесту. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByEventId(eventId: String): List<ReceivedEventsRow> {
        return dbWarehouseExec {
            ReceivedEventsTable.selectAll().where { ReceivedEventsTable.eventId eq UUID.fromString(eventId) }.map {
                ReceivedEventsRow(
                    storeId = it[ReceivedEventsTable.storeId],
                    deliveryId = it[ReceivedEventsTable.deliveryId],
                    eventId = it[ReceivedEventsTable.eventId]
                )
            }
        }
    }
}
