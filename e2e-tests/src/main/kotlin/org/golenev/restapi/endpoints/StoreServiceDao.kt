package org.golenev.restapi.endpoints

import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor
import org.golenev.restapi.config.Reply
import org.golenev.commondto.PutCartItem
import org.golenev.commondto.SubmitCart

/** REST-операции сервиса STORE в изолированном окружении; каждый запрос имеет собственную спецификацию. */
class StoreServiceDao : RequestExecutor(Environment.STORE_URL) {
    /** Читает каталог выбранного магазина; ожидаемый статус и DTO задаёт вызывающий сценарий. */
    fun getCatalog(storeId: String): Reply = request("/stores/$storeId/catalog")

    /** Создаёт независимую корзину без резервирования товара. */
    fun createCart(storeId: String): Reply = request("/stores/$storeId/carts", "POST")

    /** Читает корзину в границах магазина; чужой идентификатор позволяет проверить отказ доступа по контракту. */
    fun getCart(storeId: String, cartId: String): Reply = request("/stores/$storeId/carts/$cartId")

    /** Устанавливает количество с явно переданной версией; исходные данные не заменяются при повторном запросе. */
    fun putCartItem(storeId: String, cartId: String, stockItemId: String, body: PutCartItem): Reply {
        return request("/stores/$storeId/carts/$cartId/items/$stockItemId", "PUT", body)
    }

    /** Оформляет исходную версию корзины с постоянным ключом идемпотентности. Ожидаемый успех или конфликт проверяет тест. */
    fun submitCart(storeId: String, cartId: String, body: SubmitCart, key: String): Reply {
        return request("/stores/$storeId/carts/$cartId/submit", "POST", body, key)
    }

    /** Читает сохранённое состояние принятой операции, не повторяя списание товара. */
    fun getSubmission(storeId: String, submissionId: String): Reply = request("/stores/$storeId/submissions/$submissionId")
}
