package org.golenev.config

/** Параметры отдельных баз изолированного запуска; роли и пароли соответствуют compose.e2e.yml. */
object Environment {
    val DB_PORT = System.getenv("E2E_POSTGRES_PORT") ?: error("Запускайте E2E через scripts/run-e2e.py: не задан E2E_POSTGRES_PORT")
    val STORE_URL = System.getenv("E2E_STORE_URL") ?: "http://localhost:18889"
    val WAREHOUSE_URL = System.getenv("E2E_WAREHOUSE_URL") ?: "http://localhost:18891"
    val TARIFFS_URL = System.getenv("E2E_TARIFFS_URL") ?: "http://localhost:18890"
    val KAFKA_BOOTSTRAP = System.getenv("E2E_KAFKA") ?: "localhost:19093"
    const val DB_DRIVER = "org.postgresql.Driver"
}
