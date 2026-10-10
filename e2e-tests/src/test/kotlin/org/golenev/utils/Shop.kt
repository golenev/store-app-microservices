package org.golenev.utils

import io.qameta.allure.Step
import io.restassured.response.Response
import org.golenev.commondto.*
import org.golenev.restapi.endpoints.StoreServiceDao
import org.golenev.restapi.endpoints.WarehouseServiceDao

/** Пара идентификаторов магазина и города для подготовки теста. */
typealias Scope = Pair<String, String>
/** Возвращает идентификатор магазина — владельца данных STORE и WAREHOUSE. */
val Scope.store: String get() = first

/**
 * Поддерживает используемые HTML-сценариями операции поставки, чтения остатка и изменения корзины.
 * Остаток читается через публичный каталог; бизнес-проверки и ожидания выполняются в тестах.
 */
object Shop {
    private val warehouseService = WarehouseServiceDao()
    private val storeService = StoreServiceDao()

    /** Передаёт поставку через API поставщика. Подтверждение Kafka ещё не означает расчёт цены или зачисление в STORE. */
    @Step("Передаём поставку {event.payload.deliveryId}")
    fun publish(event: DeliveryReceived) {
        warehouseService.sendDelivery(event)
    }

    /** Возвращает типизированный каталог магазина. Порядок позиций не предполагается. */
    @Step("Читаем каталог магазина {scope}")
    fun catalog(scope: Scope): Catalog {
        return storeService.getCatalog(scope.store).`as`(Catalog::class.java)
    }

    /** Находит единственную позицию по productId; отсутствие или неоднозначный результат возвращает null для проверки в тесте. */
    @Step("Ищем товар {product} в магазине {scope}")
    fun stock(scope: Scope, product: String): Stock? {
        val matching = catalog(scope).items.filter { it.productId == product }
        return matching.singleOrNull()
    }

    /** Устанавливает количество позиции. Ожидаемый статус успеха или отказа явно проверяет вызывающий сценарий. */
    @Step("Изменяем количество позиции в корзине")
    fun put(scope: Scope, cart: Cart, stock: Stock, quantity: Int, expectedStatus: Int = 200): Response {
        return storeService.putCartItem(scope.store, cart.cartId, stock.stockItemId, PutCartItem(quantity, cart.version), expectedStatus)
    }
}
