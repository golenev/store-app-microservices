package org.golenev.ui.config
import org.golenev.restapi.endpoints.*

/** Неизменяемые данные браузерного запроса для диагностики. Метод, путь, ключ и тело сохраняются как отдельные признаки сопоставления. */
data class BrowserRequest(val method: String, val path: String, val key: String?, val body: String, val headers: Map<String, List<String>>)
