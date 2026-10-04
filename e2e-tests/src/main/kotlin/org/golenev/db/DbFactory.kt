package org.golenev.db

import org.golenev.config.Environment
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.ConcurrentHashMap

/** Выбирает подключение Exposed по сервису. Кеш подключений потокобезопасен и не задаёт общую базу по умолчанию. */
object DbFactory {
    private val databases = ConcurrentHashMap<DbType, Database>()

    /** Выполняет блок в одной транзакции выбранной базы: успех фиксируется, ошибка откатывает изменения. Автоповторы отключены для проверок управляемого сбоя. */
    fun <T> transaction(dbType: DbType, statement: Transaction.() -> T): T {
        val database = databases.computeIfAbsent(dbType) { type ->
            Database.connect("jdbc:postgresql://localhost:${Environment.DB_PORT}/${type.service}_db",
                driver = Environment.DB_DRIVER, user = "${type.service}_app", password = "${type.service}_local")
        }
        return transaction(db = database) {
            repetitionAttempts = 1
            statement()
        }
    }
}

/** Выполняет блок в отдельной транзакции STORE; исключения передаются после отката. */
fun <T> dbStoreExec(block: Transaction.() -> T): T = DbFactory.transaction(DbType.STORE, block)
/** Выполняет блок в отдельной транзакции WAREHOUSE; исключения передаются после отката. */
fun <T> dbWarehouseExec(block: Transaction.() -> T): T = DbFactory.transaction(DbType.WAREHOUSE, block)
/** Выполняет блок в отдельной транзакции TARIFFS; исключения передаются после отката. */
fun <T> dbTariffsExec(block: Transaction.() -> T): T = DbFactory.transaction(DbType.TARIFFS, block)
