package org.golenev.utils

import org.golenev.utils.awaitState
import org.golenev.utils.required
import com.codeborne.selenide.logevents.SelenideLogger
import org.golenev.ui.config.BrowserScenario
import org.golenev.db.tables.observation.ObservationDao
import io.qameta.allure.Allure
import java.util.UUID

/** Явная функция-шаблон управляет ресурсами UI. Обратная очистка сохраняет исходную ошибку проверки, дополняя её ошибками восстановления. */
fun withBrowserTemplate(body: (BrowserScenario) -> Unit) {
    withShopTemplate { resources ->
        resources.onClose("Отключаем обработчик событий Selenide этого сценария") { SelenideLogger.removeListener<com.codeborne.selenide.logevents.LogEventListener>("ReadableAllureSelenide"); org.golenev.ui.allure.UiElementNameRegistry.clear() }
        body(BrowserScenario(resources))
    }
}
