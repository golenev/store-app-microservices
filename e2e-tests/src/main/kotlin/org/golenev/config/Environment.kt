package org.golenev.config

/** Адреса штатного Docker Compose. Переменные E2E позволяют направить тесты на другой адрес без отдельного профиля приложения. */
object Environment {
    val DB_PORT = System.getenv("E2E_POSTGRES_PORT") ?: "34567"
    val STORE_URL = System.getenv("E2E_STORE_URL") ?: "http://localhost:6789"
    val WAREHOUSE_URL = System.getenv("E2E_WAREHOUSE_URL") ?: "http://localhost:6791"
    val TARIFFS_URL = System.getenv("E2E_TARIFFS_URL") ?: "http://localhost:6790"
    val KAFKA_BOOTSTRAP = System.getenv("E2E_KAFKA") ?: "localhost:9092"
    const val DB_DRIVER = "org.postgresql.Driver"
}
