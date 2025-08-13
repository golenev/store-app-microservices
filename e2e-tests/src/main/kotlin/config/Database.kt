package config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

object Database {
    private val template = JdbcTemplate(
        DriverManagerDataSource(
            "jdbc:postgresql://localhost:34567/mydatabase",
            "myuser",
            "mypassword"
        )
    )

    fun template(): JdbcTemplate = template
}

