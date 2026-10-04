package config

import java.sql.DriverManager

/** Bound SQL observation and fault fixtures for the launcher's dedicated databases; there is no mutable JDBC singleton. */
object Database {
    /** Reads an ordered/filtered identifier collection; SQL errors propagate and an empty result is legitimate absence. */
    fun strings(service: String, sql: String, vararg args: Any?): List<String> {
        return DriverManager.getConnection(url(service), "${service}_app", "${service}_local").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
    }
    /** Resolves a service role on the explicitly isolated PostgreSQL port; callers choose STORE/WAREHOUSE/TARIFFS ownership. */
    private fun url(service: String): String = "jdbc:postgresql://localhost:${System.getenv("E2E_POSTGRES_PORT") ?: "35467"}/${service}_db"

    /** Executes bound fixture/gate SQL in autocommit; caller owns restoration and may not write inventory as E2E setup. */
    fun update(service: String, sql: String, vararg args: Any?): Int {
        return DriverManager.getConnection(url(service), "${service}_app", "${service}_local").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    /** Returns zero/one scalar with bound identifiers; unexpected multiplicity and SQL failures propagate immediately. */
    fun scalar(service: String, sql: String, vararg args: Any?): String? {
        return DriverManager.getConnection(url(service), "${service}_app", "${service}_local").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val result = rows.getString(1)
                        check(!rows.next()) { "Ambiguous scalar observation service=$service SQL=$sql" }
                        result
                    }
                }
            }
        }
    }
}
