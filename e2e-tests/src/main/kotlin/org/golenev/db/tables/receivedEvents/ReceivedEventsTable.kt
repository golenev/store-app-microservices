package org.golenev.db.tables.receivedEvents

import org.jetbrains.exposed.sql.Table

/** Колонки received_events для явных запросов тестов; схема принадлежит приложению. */
object ReceivedEventsTable : Table("received_events") {
    val storeId = varchar("store_id", 64)
    val deliveryId = varchar("delivery_id", 64)
    val eventId = uuid("event_id")
}
