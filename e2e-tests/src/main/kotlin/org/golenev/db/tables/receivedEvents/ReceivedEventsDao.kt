package org.golenev.db.tables.receivedEvents

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.selectAll
import java.util.*

/** Явные запросы ReceivedEvents через Exposed; DAO возвращает данные без проверок тестовых инвариантов. */
object ReceivedEventsDao {

    /** Считает обработанное событие по eventId; ожидание результата выполняется в тесте. */
    fun countByEventId(eventId: String): Long {
        return dbWarehouseExec { ReceivedEventsTable.selectAll().where { ReceivedEventsTable.eventId eq UUID.fromString(eventId) }.count() }
    }
}
