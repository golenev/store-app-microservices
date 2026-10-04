package org.golenev.utils


import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.restassured.response.Response
import org.golenev.commondto.*
import org.golenev.db.tables.warehouseOutbox.WarehouseOutboxDao
import org.golenev.restapi.endpoints.StoreServiceDao
import org.golenev.restapi.endpoints.WarehouseServiceDao
import java.time.Instant
import java.util.*

/** Пара идентификаторов магазина и города для подготовки теста. */
typealias Scope = Pair<String, String>
/** Возвращает идентификатор магазина — владельца данных STORE и WAREHOUSE. */
val Scope.store: String get() = first
/** Возвращает город, определяющий выбор тарифного правила. */
val Scope.city: String get() = second

/**
 * Создаёт предусловия каждого теста: собственную поставку, доступный остаток и при необходимости наполненную корзину.
 * Товар поступает через настоящую цепочку WAREHOUSE → Kafka → STORE; прямой вставки остатков здесь нет.
 * Идентификаторы поставок и продуктов уникальны, поэтому сохранённые данные прошлого теста не служат предусловием следующего.
 * Дополнительно читает состояние поставки и результат оформления; контейнерами не управляет.
 * Бизнес-проверки остаются явно в тестах; вложенные технические шаги показывают отправку и ожидание результата.
 */
object Shop {
    /** Создаёт уникальный идентификатор города для собственных тарифных правил теста. */
    @io.qameta.allure.Step("Подготавливаем город для тарифных правил")
    fun city(): String = "C-${UUID.randomUUID()}"

    /** Создаёт магазин в WAREHOUSE и его область данных STORE. Остатки затем формируются настоящей поставкой; данные сохраняются после теста. */
    @io.qameta.allure.Step("Создаём магазин в городе {city}")
    fun scope(city: String = "MOSCOW"): Scope {
        val store = "S-${UUID.randomUUID()}"
        org.golenev.db.tables.stores.StoresDao.insert(store, city)
        org.golenev.db.tables.storeScopes.StoreScopesDao.insert(store)
        return store to city
    }

    private val warehouseService = WarehouseServiceDao()

    private val storeService = StoreServiceDao()

    /** Создаёт неизменяемые данные поставки с независимыми идентификаторами и точными денежными строками. */
    @io.qameta.allure.Step("Формируем поставку для магазина {scope}")
    fun delivery(scope: Scope, product: String = "P-${UUID.randomUUID()}", quantity: Int = 10,
    price: String = "100.00", type: String = "NON_FOOD"): DeliveryReceived {
        return DeliveryReceived(eventId = UUID.randomUUID().toString(), occurredAt = Instant.now().toString(), storeId = scope.store,
        payload = DeliveryPayload("D-${UUID.randomUUID()}", listOf(DeliveryLine("L-1", product, type, "Учебный $product", "E2E", quantity, price))))
    }

    /** Передаёт поставку через API поставщика. Подтверждение Kafka ещё не означает расчёт цены или зачисление в STORE. */
    @io.qameta.allure.Step("Передаём поставку {event.payload.deliveryId}")
    fun publish(event: DeliveryReceived) {
        warehouseService.sendDelivery(event)
    }

    /** Читает состояние приёмки с ожидаемым статусом 200. До создания приёмки awaitPoll повторяет проверку статуса; ошибки не превращаются в пустой результат. */
    @io.qameta.allure.Step("Читаем состояние приёмки поставки {event.payload.deliveryId}")
    fun receiving(event: DeliveryReceived): Receiving? {
        val reply = warehouseService.getDelivery(event.storeId, event.payload.deliveryId)
        return reply.`as`(Receiving::class.java)
    }

    /** Ждёт заданное состояние той же поставки. Блок awaitPoll читает ту же приёмку и проверяет ожидаемое состояние матчерами Kotest. */
    @io.qameta.allure.Step("Ожидаем состояние {state} поставки {event}")
    fun received(event: DeliveryReceived, state: DeliveryState = DeliveryState.POSTED): Receiving {
        val result = awaitPoll {
            val actual = receiving(event)
            withClue("Поставка ${event.payload.deliveryId}: ожидается $state") { (actual?.state == state).shouldBeTrue() }
            actual
        }
        return result.shouldNotBeNull()
    }

    /** Возвращает типизированный каталог магазина. Порядок позиций не предполагается. */
    @io.qameta.allure.Step("Читаем каталог магазина {scope}")
    fun catalog(scope: Scope): Catalog {
        return storeService.getCatalog(scope.store).`as`(Catalog::class.java)
    }

    /** Находит единственную позицию по productId; отсутствие или неоднозначный результат возвращает null для проверки в тесте. */
    @io.qameta.allure.Step("Ищем товар {product} в магазине {scope}")
    fun stock(scope: Scope, product: String): Stock? {
        val matching = catalog(scope).items.filter { it.productId == product }
        return matching.singleOrNull()
    }

    /** Ждёт явно ожидаемое количество в STORE. Одного состояния POSTED в WAREHOUSE для этого недостаточно. */
    @io.qameta.allure.Step("Ожидаем количество {expectedQuantity} после поставки")
    fun stocked(scope: Scope, event: DeliveryReceived, expectedQuantity: Int = event.payload.items.single().quantity): Stock {
        val product = event.payload.items.single().productId
        val result = awaitPoll {
            val actual = stock(scope, product)
            withClue("Продукт $product: ожидается $expectedQuantity единиц") { (actual?.availableQuantity == expectedQuantity).shouldBeTrue() }
            actual
        }
        return result.shouldNotBeNull()
    }

    /** Готовит остаток через настоящую поставку. Отличающиеся от стандартных данные вызывающий код задаёт до действия. */
    @io.qameta.allure.Step("Проводим поставку и ожидаем доступный остаток")
    fun supply(scope: Scope, event: DeliveryReceived = delivery(scope)): Stock {
        publish(event)
        received(event)
        return stocked(scope, event)
    }

    /** Создаёт отдельную серверную корзину магазина. */
    @io.qameta.allure.Step("Создаём корзину магазина {scope}")
    fun cart(scope: Scope): Cart {
        return storeService.createCart(scope.store).`as`(Cart::class.java)
    }

    /** Читает текущую корзину либо неизменяемый состав принятой заявки по исходному идентификатору. */
    @io.qameta.allure.Step("Читаем корзину магазина {scope}")
    fun getCart(scope: Scope, cart: Cart): Cart {
        return storeService.getCart(scope.store, cart.cartId).`as`(Cart::class.java)
    }

    /** Устанавливает количество позиции. Ожидаемый статус успеха или отказа явно проверяет вызывающий сценарий. */
    @io.qameta.allure.Step("Изменяем количество позиции в корзине")
    fun put(scope: Scope, cart: Cart, stock: Stock, quantity: Int, expectedStatus: Int = 200): Response {
        return storeService.putCartItem(scope.store, cart.cartId, stock.stockItemId, PutCartItem(quantity, cart.version), expectedStatus)
    }

    /** Создаёт и наполняет независимую корзину через публичный API с проверкой версии. Остаток не резервируется. */
    @io.qameta.allure.Step("Добавляем товар в новую корзину")
    fun filled(scope: Scope, stock: Stock, quantity: Int): Cart {
        return put(scope, cart(scope), stock, quantity).`as`(Cart::class.java)
    }

    /** Отправляет сохранённую версию корзины и постоянный ключ операции. Новая версия не подставляется вместо исходного повторного запроса. */
    @io.qameta.allure.Step("Отправляем запрос оформления корзины")
    fun submit(scope: Scope, cart: Cart, key: String = UUID.randomUUID().toString(), expectedStatus: Int = 202): Response {
        return storeService.submitCart(scope.store, cart.cartId, SubmitCart(cart.version), key, expectedStatus)
    }

    /** Ожидает публикацию принятой заявки через awaitPoll, не повторяя транзакцию списания. */
    @io.qameta.allure.Step("Ожидаем публикацию принятой заявки")
    fun published(scope: Scope, accepted: Submission): Submission {
        return awaitPoll {
            val actual = storeService.getSubmission(scope.store, accepted.submissionId).`as`(Submission::class.java)
            withClue("Заявка ${accepted.submissionId}: ожидается PUBLISHED") { (actual.publicationStatus == PublicationStatus.PUBLISHED).shouldBeTrue() }
            actual
        }
    }

    /** Читает сохранённое неизменяемое исходящее событие. Оно служит исходным снимком для проверки повторов и сохранности данных. */
    @io.qameta.allure.Step("Читаем сохранённое событие прихода")
    fun goods(event: DeliveryReceived): GoodsPosted {
        val raw = WarehouseOutboxDao.findPayloadByDeliveryId(event.storeId, event.payload.deliveryId).shouldNotBeNull()
        return JsonUtils.objectMapper.readValue(raw)
    }

}
