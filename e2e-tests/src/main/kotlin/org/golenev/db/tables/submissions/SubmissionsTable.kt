package org.golenev.db.tables.submissions

import org.jetbrains.exposed.sql.Table

/** Колонки submissions, необходимые для наблюдения своего магазина; таблица приложения не создаётся и не изменяется через SchemaUtils. */
object SubmissionsTable : Table("submissions") {
    val storeId = varchar("store_id", 64)
}
