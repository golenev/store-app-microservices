package org.golenev.db.tables.warehouseOutbox

import org.jetbrains.exposed.sql.Table

/** Колонки warehouse_outbox, необходимые для наблюдения своего магазина; таблица приложения не создаётся и не изменяется через SchemaUtils. */
object WarehouseOutboxTable : Table("warehouse_outbox") {
    val storeId = varchar("store_id", 64)
    val payload = text("payload")
    val lastError = text("last_error").nullable()
    val publicationStatus = varchar("publication_status", 32)
}
