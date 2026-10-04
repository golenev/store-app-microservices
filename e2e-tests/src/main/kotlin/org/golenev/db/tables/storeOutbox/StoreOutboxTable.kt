package org.golenev.db.tables.storeOutbox

import org.jetbrains.exposed.sql.Table

/** Колонки store_outbox, необходимые для наблюдения своего магазина; таблица приложения не создаётся и не изменяется через SchemaUtils. */
object StoreOutboxTable : Table("store_outbox") {
    val storeId = varchar("store_id", 64)
    val payload = text("payload")
    val lastError = text("last_error").nullable()
    val publicationStatus = varchar("publication_status", 32)
    val submissionId = uuid("submission_id")
    val eventId = uuid("event_id")
}
