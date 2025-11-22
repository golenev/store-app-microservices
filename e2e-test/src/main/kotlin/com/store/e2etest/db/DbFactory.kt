package com.store.e2etest.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.transactions.transaction

enum class DbType {
    TARIFFS
}

object DbFactory {

    private val databases by lazy { mutableMapOf<DbType, Database>() }

    private fun connect(dbType: DbType): Database {
        val driver = "org.postgresql.Driver"
        return when (dbType) {
            DbType.TARIFFS -> Database.connect(
                url = "jdbc:postgresql://localhost:34568/tariffs_db",
                driver = driver,
                user = "myuser",
                password = "mypassword"
            )
        }
    }

    fun <T> transaction(dbType: DbType, statement: org.jetbrains.exposed.sql.transactions.Transaction.() -> T): T {
        val database = databases.getOrPut(dbType) { connect(dbType) }
        return transaction(db = database) {
            addLogger(StdOutSqlLogger)
            statement()
        }
    }
}

fun <T> dbTariffsExec(block: org.jetbrains.exposed.sql.transactions.Transaction.() -> T): T =
    DbFactory.transaction(DbType.TARIFFS, block)
