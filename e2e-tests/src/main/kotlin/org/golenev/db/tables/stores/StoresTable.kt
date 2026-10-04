package org.golenev.db.tables.stores

import org.jetbrains.exposed.sql.Table

/** Колонки stores для явных запросов тестов; схема принадлежит приложению. */
object StoresTable : Table("stores") {
    val storeId = varchar("store_id", 64)
    val city = varchar("city", 64)
}
