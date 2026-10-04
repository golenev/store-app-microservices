package org.golenev.restapi.endpoints

import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor
import org.golenev.restapi.config.Reply
import org.golenev.commondto.DeliveryReceived

/** REST-операции сервиса WAREHOUSE в изолированном окружении; каждый запрос имеет собственную спецификацию. */
class WarehouseServiceDao : RequestExecutor(Environment.WAREHOUSE_URL) {
    /** Публикует неизменяемую поставку настоящему брокеру; HTTP-подтверждение ещё не означает оприходование в STORE. */
    fun sendDelivery(body: DeliveryReceived): Reply = request("/technical/deliveries", "POST", body)

    /** Читает состояние конкретной приёмки; только 404 означает отсутствие сохранённой поставки. */
    fun getDelivery(storeId: String, deliveryId: String): Reply = request("/stores/$storeId/deliveries/$deliveryId")

    /** Запрашивает диагностический повтор существующей приёмки; новую поставку не создаёт. */
    fun retryDelivery(storeId: String, deliveryId: String): Reply = request("/stores/$storeId/deliveries/$deliveryId/retry-pricing", "POST")
}
