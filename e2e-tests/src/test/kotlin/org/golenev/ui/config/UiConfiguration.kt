package org.golenev.ui.config
import org.golenev.restapi.endpoints.*

/** Публичная конфигурация UI. Прокси меняет только адрес WAREHOUSE на свой локальный маршрут того же источника. */
data class UiConfiguration(val warehouseBaseUrl: String)
