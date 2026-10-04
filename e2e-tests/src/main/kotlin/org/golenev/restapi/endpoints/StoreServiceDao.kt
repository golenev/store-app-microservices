package org.golenev.restapi.endpoints

import io.qameta.allure.Step
import io.restassured.response.Response
import org.golenev.commondto.PutCartItem
import org.golenev.commondto.SubmitCart
import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor

/** REST-операции сервиса STORE на заданном адресе; каждый запрос имеет собственную спецификацию и явно ожидаемый статус. */
class StoreServiceDao : RequestExecutor(Environment.STORE_URL) {
    /** Читает каталог выбранного магазина; ожидаемый статус и DTO задаёт вызывающий сценарий. */
    @Step("Читаем каталог магазина {storeId}")
    fun getCatalog(storeId: String, expectedStatus: Int = 200): Response =
        getRequest(url = "/stores/$storeId/catalog", spec = baseRequest(), expectedStatus = expectedStatus)

    /** Создаёт независимую корзину без резервирования товара. */
    @Step("Создаём корзину магазина {storeId}")
    fun createCart(storeId: String, expectedStatus: Int = 201): Response =
        postRequest(url = "/stores/$storeId/carts", spec = baseRequest(), expectedStatus = expectedStatus)

    /** Читает корзину в границах магазина; чужой идентификатор позволяет проверить отказ доступа по контракту. */
    @Step("Читаем корзину {cartId} магазина {storeId}")
    fun getCart(storeId: String, cartId: String, expectedStatus: Int = 200): Response =
        getRequest(url = "/stores/$storeId/carts/$cartId", spec = baseRequest(), expectedStatus = expectedStatus)

    /** Устанавливает количество с явно переданной версией; исходные данные не заменяются при повторном запросе. */
    @Step("Изменяем позицию {stockItemId} корзины {cartId}")
    fun putCartItem(storeId: String, cartId: String, stockItemId: String, body: PutCartItem, expectedStatus: Int = 200): Response {
        return putRequest(
            url = "/stores/$storeId/carts/$cartId/items/$stockItemId",
            spec = baseRequest().body(body),
            expectedStatus = expectedStatus
        )
    }

    /** Оформляет исходную версию корзины с постоянным ключом идемпотентности. Ожидаемый успех или конфликт проверяет тест. */
    @Step("Оформляем корзину {cartId} с ключом {key}")
    fun submitCart(storeId: String, cartId: String, body: SubmitCart, key: String, expectedStatus: Int = 202): Response {
        return postRequest(
            url = "/stores/$storeId/carts/$cartId/submit",
            spec = baseRequest().body(body).header("Idempotency-Key", key),
            expectedStatus = expectedStatus
        )
    }

    /** Читает сохранённое состояние принятой операции, не повторяя списание товара. */
    @Step("Читаем заявку {submissionId} магазина {storeId}")
    fun getSubmission(storeId: String, submissionId: String, expectedStatus: Int = 200): Response =
        getRequest(
            url = "/stores/$storeId/submissions/$submissionId",
            spec = baseRequest(),
            expectedStatus = expectedStatus
        )
}
