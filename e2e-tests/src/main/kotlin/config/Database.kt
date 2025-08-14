package config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import io.qameta.allure.Allure

object Database {
    private val template = JdbcTemplate(
        DriverManagerDataSource(
            "jdbc:postgresql://localhost:34567/mydatabase",
            "myuser",
            "mypassword"
        )
    )

    fun template(): JdbcTemplate = template

    fun update(sql: String, vararg args: Any): Int {
        Allure.addAttachment("SQL query", sql)
        return template.update(sql, *args)
    }

    fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any): T {
        Allure.addAttachment("SQL query", sql)
        return template.queryForObject(sql, requiredType, *args)
    }
}

