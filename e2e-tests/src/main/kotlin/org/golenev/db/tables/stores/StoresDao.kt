package org.golenev.db.tables.stores

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.insert

/** Явные запросы Stores через Exposed; DAO возвращает данные без проверок тестовых инвариантов. */
object StoresDao {


    /** Создаёт магазин с городом для независимого теста; ошибка ограничения откатывает вставку. */
    fun insert(storeId: String, city: String) {
        dbWarehouseExec {
            StoresTable.insert {
                it[StoresTable.storeId] = storeId
                it[StoresTable.city] = city
            }
        }
    }
}
