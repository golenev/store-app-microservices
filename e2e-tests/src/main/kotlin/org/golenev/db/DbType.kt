package org.golenev.db

/** База и отдельная роль сервиса. Произвольное имя базы не принимается. */
enum class DbType(val service: String) { STORE("store"), WAREHOUSE("warehouse"), TARIFFS("tariffs") }
