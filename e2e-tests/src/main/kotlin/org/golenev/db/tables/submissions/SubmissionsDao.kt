package org.golenev.db.tables.submissions

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.selectAll

/** Типизированные наблюдения submissions в отдельной транзакции STORE. */
object SubmissionsDao {
    /** Считает зафиксированные строки своего магазина; нулевое количество возвращается явно. */
    fun countByStoreId(storeId: String): Long {
        return dbStoreExec { SubmissionsTable.selectAll().where { SubmissionsTable.storeId eq storeId }.count() }
    }
}
