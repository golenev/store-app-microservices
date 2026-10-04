package org.golenev.restapi.endpoints

import io.qameta.allure.Step
import io.restassured.response.Response
import org.golenev.commondto.DeliveryReceived
import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor

/** REST-операции сервиса WAREHOUSE на заданном адресе; каждый запрос имеет собственную спецификацию и явно ожидаемый статус. */
class WarehouseServiceDao : RequestExecutor(Environment.WAREHOUSE_URL) {
    /** Публикует неизменяемую поставку настоящему брокеру; HTTP-подтверждение ещё не означает оприходование в STORE. */
    @Step("Отправляем событие поставки в WAREHOUSE")
    fun sendDelivery(body: DeliveryReceived, expectedStatus: Int = 202): Response =
        postRequest(url = "/technical/deliveries", spec = baseRequest().body(body), expectedStatus = expectedStatus)

    /** Читает состояние конкретной приёмки; ожидаемый статус задаёт сценарий. */
    @Step("Читаем приёмку поставки {deliveryId} магазина {storeId}")
    fun getDelivery(storeId: String, deliveryId: String, expectedStatus: Int = 200): Response =
        getRequest(
            url = "/stores/$storeId/deliveries/$deliveryId",
            spec = baseRequest(),
            expectedStatus = expectedStatus
        )

    /** Запрашивает диагностический повтор существующей приёмки; новую поставку не создаёт. */
    @Step("Повторяем расчёт поставки {deliveryId}")
    fun retryDelivery(storeId: String, deliveryId: String, expectedStatus: Int = 202): Response =
        postRequest(
            url = "/stores/$storeId/deliveries/$deliveryId/retry-pricing",
            spec = baseRequest(),
            expectedStatus = expectedStatus
        )
}
