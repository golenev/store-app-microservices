package org.golenev.utils

import org.golenev.db.tables.observation.ObservationDao
import org.golenev.restapi.endpoints.*
import io.lettuce.core.RedisClient
import io.qameta.allure.Allure
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID

/** Выполняет явно заданный сценарий в проверенном изолированном окружении. Конструкция use сохраняет исходную ошибку и добавляет ошибки очистки как suppressed. */
fun withShopTemplate(body: (ScenarioResources) -> Unit) {
    ScenarioResources().use { resources ->
        Allure.label("testRevision", System.getProperty("e2e.revision", "local"))
        Allure.label("testSourceHash", System.getProperty("e2e.testSourceHash", "local"))
        Allure.label("composeProject", Shop.project())
        step("Подготавливаем независимое тестовое окружение") {
            for (service in listOf("store", "warehouse")) {
                assertEquals("e2e_gates", ObservationDao.scalar(service, "SELECT to_regclass('e2e_gates')::text"), "Explicit e2e profile: $service")
            }
            resources.preserveCache()
        }
        body(resources)
    }
}
