package org.golenev.restapi.endpoints

import io.restassured.response.Response
import org.golenev.commondto.PutCartItem
import org.golenev.commondto.SubmitCart
import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor

/** REST-операции сервиса STORE на заданном адресе; каждый запрос имеет собственную спецификацию и явно ожидаемый статус. */
class StoreServiceDao : RequestExecutor(Environment.STORE_URL) {
    /** Читает каталог выбранного магазина; ожидаемый статус и DTO задаёт вызывающий сценарий. */
    @io.qameta.allure.Step("Читаем каталог магазина {storeId}")
    fun getCatalog(storeId: String, expectedStatus: Int = 200): Response = getRequest("/stores/$storeId/catalog", baseRequest(), expectedStatus)

    /** Создаёт независимую корзину без резервирования товара. */
    @io.qameta.allure.Step("Создаём корзину магазина {storeId}")
    fun createCart(storeId: String, expectedStatus: Int = 201): Response = postRequest("/stores/$storeId/carts", baseRequest(), expectedStatus)

    /** Читает корзину в границах магазина; чужой идентификатор позволяет проверить отказ доступа по контракту. */
    @io.qameta.allure.Step("Читаем корзину {cartId} магазина {storeId}")
    fun getCart(storeId: String, cartId: String, expectedStatus: Int = 200): Response = getRequest("/stores/$storeId/carts/$cartId", baseRequest(), expectedStatus)

    /** Устанавливает количество с явно переданной версией; исходные данные не заменяются при повторном запросе. */
    @io.qameta.allure.Step("Изменяем позицию {stockItemId} корзины {cartId}")
    fun putCartItem(storeId: String, cartId: String, stockItemId: String, body: PutCartItem, expectedStatus: Int = 200): Response {
        return putRequest("/stores/$storeId/carts/$cartId/items/$stockItemId", baseRequest().body(body), expectedStatus)
    }

    /** Оформляет исходную версию корзины с постоянным ключом идемпотентности. Ожидаемый успех или конфликт проверяет тест. */
    @io.qameta.allure.Step("Оформляем корзину {cartId} с ключом {key}")
    fun submitCart(storeId: String, cartId: String, body: SubmitCart, key: String, expectedStatus: Int = 202): Response {
        return postRequest("/stores/$storeId/carts/$cartId/submit", baseRequest().body(body).header("Idempotency-Key", key), expectedStatus)
    }

    /** Читает сохранённое состояние принятой операции, не повторяя списание товара. */
    @io.qameta.allure.Step("Читаем заявку {submissionId} магазина {storeId}")
    fun getSubmission(storeId: String, submissionId: String, expectedStatus: Int = 200): Response = getRequest("/stores/$storeId/submissions/$submissionId", baseRequest(), expectedStatus)
}
