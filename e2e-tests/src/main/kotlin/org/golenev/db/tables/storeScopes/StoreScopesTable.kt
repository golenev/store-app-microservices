package org.golenev.db.tables.storeScopes

import org.jetbrains.exposed.sql.Table

/** Колонки store_scopes для явных запросов тестов; схема принадлежит приложению. */
object StoreScopesTable : Table("store_scopes") {
    val storeId = varchar("store_id", 64)
}
