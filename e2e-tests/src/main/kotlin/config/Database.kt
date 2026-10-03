package config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import io.qameta.allure.Allure

object Database {
    private val template = JdbcTemplate(
        DriverManagerDataSource(
            System.getenv("STORE_DB_URL") ?: "jdbc:postgresql://localhost:34567/store_db",
            System.getenv("STORE_DB_USER") ?: "store_app",
            System.getenv("STORE_DB_PASSWORD") ?: "store_local"
        )
    )

    /** Returns the legacy STORE JDBC client configured by STORE_DB_* variables. */
    fun template(): JdbcTemplate = template

    /** Mutates the database with bound arguments and attaches SQL to Allure. */
    fun update(sql: String, vararg args: Any): Int {
        Allure.addAttachment("SQL query", sql)
        return template.update(sql, *args)
    }

    /** Reads one typed value; missing or multiple rows propagate JDBC errors. */
    fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any): T {
        Allure.addAttachment("SQL query", sql)
        return template.queryForObject(sql, requiredType, *args)
    }
}

