package config

import java.sql.DriverManager

/** Bound SQL observation and fault fixtures for the launcher's dedicated databases; there is no mutable JDBC singleton. */
object Database {
    /** Resolves a service role on the explicitly isolated PostgreSQL port; callers choose STORE/WAREHOUSE/TARIFFS ownership. */
    private fun url(service: String): String = "jdbc:postgresql://localhost:${System.getenv("E2E_POSTGRES_PORT") ?: "35467"}/${service}_db"

    /** Executes bound fixture/gate SQL in autocommit; caller owns restoration and may not write inventory as E2E setup. */
    fun update(service: String, sql: String, vararg args: Any?): Int =
        DriverManager.getConnection(url(service), "${service}_app", "${service}_local").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }

    /** Returns one scalar observation with bound identifiers; absence is null and SQL failures propagate immediately. */
    fun scalar(service: String, sql: String, vararg args: Any?): String? =
        DriverManager.getConnection(url(service), "${service}_app", "${service}_local").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
}
