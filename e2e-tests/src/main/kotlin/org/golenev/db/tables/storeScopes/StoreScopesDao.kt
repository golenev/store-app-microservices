package org.golenev.db.tables.storeScopes

import org.golenev.db.dbStoreExec
import org.jetbrains.exposed.sql.insert

/** Явные запросы StoreScopes через Exposed; DAO возвращает данные без проверок тестовых инвариантов. */
object StoreScopesDao {


    /** Создаёт область STORE для независимого теста; остаток не добавляется. */
    fun insert(storeId: String) {
        dbStoreExec { StoreScopesTable.insert { it[StoreScopesTable.storeId] = storeId } }
    }
}
