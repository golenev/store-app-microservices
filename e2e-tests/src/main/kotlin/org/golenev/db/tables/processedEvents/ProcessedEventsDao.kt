package org.golenev.db.tables.processedEvents

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll
import java.util.*

/** Явные запросы ProcessedEvents через Exposed; DAO возвращает данные без проверок тестовых инвариантов. */
object ProcessedEventsDao {

    /** Считает обработанное событие по eventId; ожидание результата выполняется в тесте. */
    fun countByEventId(eventId: String): Long {
        return dbStoreExec { ProcessedEventsTable.selectAll().where { ProcessedEventsTable.eventId eq UUID.fromString(eventId) }.count() }
    }
}
