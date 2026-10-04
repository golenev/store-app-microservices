package org.golenev.restapi.config

import org.golenev.config.Environment
import org.golenev.restapi.endpoints.*

/** Выбор DAO для проверки готовности одного из трёх сервисов после перезапуска. */
object ApiServices {
    private val tariffsService = TariffsServiceDao()

    private val warehouseService = WarehouseServiceDao()

    private val storeService = StoreServiceDao()

    /** Отправляет запрос только известному сервису; адрес выбирается из текущего изолированного окружения. */
    fun request(base: String, path: String): Reply {
        return when (base) {
            Environment.STORE_URL -> storeService.request(path)
            Environment.WAREHOUSE_URL -> warehouseService.request(path)
            Environment.TARIFFS_URL -> tariffsService.request(path)
            else -> error("Неизвестный адрес сервиса: $base")
        }
    }
}
