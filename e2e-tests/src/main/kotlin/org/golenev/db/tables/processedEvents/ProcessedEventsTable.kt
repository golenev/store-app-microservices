package org.golenev.db.tables.processedEvents

import org.jetbrains.exposed.sql.Table

/** Колонки processed_events для явных запросов тестов; схема принадлежит приложению. */
object ProcessedEventsTable : Table("processed_events") {
    val storeId = varchar("store_id", 64)
    val deliveryId = varchar("delivery_id", 64)
    val eventId = uuid("event_id")
}
