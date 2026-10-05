package org.golenev.tests.backend

import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.qameta.allure.AllureId
import org.golenev.commondto.*
import org.golenev.db.tables.inventory.InventoryDao
import org.golenev.db.tables.stockExpenses.StockExpensesDao
import org.golenev.db.tables.stockMovements.StockMovementsDao
import org.golenev.db.tables.storeOutbox.StoreOutboxDao
import org.golenev.db.tables.submissions.SubmissionsDao
import org.golenev.restapi.endpoints.StoreServiceDao
import org.golenev.utils.JsonUtils
import org.golenev.utils.Shop
import org.golenev.utils.step
import org.golenev.utils.store
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.*

/** Инварианты оформления после настоящей поставки. Каждый тест создаёт собственные предусловия; остатки создаются настоящими поставками. */
@DisplayName("Оформление заявки и независимые корзины")
class OrderE2ETest {

    private val storeService = StoreServiceDao()

    /** На остатке десять единиц, в корзине три. Оформление атомарно списывает товар, фиксирует состав и публикует одно событие заявки. */
    @Test @AllureId("16") @DisplayName("Принятая заявка атомарно списывает товар и передаёт неизменяемый состав")
    fun acceptancePersistsExactSnapshotAndEvent() {
        val expectedRemaining = 7
        val expectedTotal = "360.00"
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val (stock, cart) = step("Оприходуем 10 единиц и помещаем 3 в независимую корзину") {
            val stock = Shop.supply(scope, Shop.delivery(scope, quantity = 10, price = "100.00"))
            stock to Shop.filled(scope, stock, 3)
        }
        val accepted = step("Оформляем заявку по актуальной версии корзины") {
            Shop.submit(scope, cart).`as`(Submission::class.java)
        }
        step("Проверяем единственный расход, закрытую корзину и состав переданной заявки") {
            val published = Shop.published(scope, accepted)
            published.submissionId.shouldBe(accepted.submissionId)
            published.publishedAt.shouldNotBeNull()
            (Shop.stock(scope, stock.productId).shouldNotBeNull().availableQuantity).shouldBe(expectedRemaining)
            val closed = Shop.getCart(scope, cart)
            closed.state.shouldBe(CartState.SUBMITTED)
            closed.totalAmount.shouldBe(expectedTotal)
            closed.version.shouldBe(cart.version + 1)
            closed.submissionId.shouldBe(accepted.submissionId)
            val event = JsonUtils.objectMapper.readValue<OrderSubmitted>(StoreOutboxDao.findByStoreId(scope.store).single().payload)
            event.eventType.shouldBe("OrderSubmitted")
            event.storeId.shouldBe(scope.store)
            event.payload.submissionId.shouldBe(accepted.submissionId)
            event.payload.cartId.shouldBe(cart.cartId)
            event.payload.totalAmount.shouldBe(expectedTotal)
            (event.payload.items.single().quantity).shouldBe(3)
            (event.payload.items.single().unitPrice).shouldBe("120.00")
            (StockExpensesDao.findByStoreId(scope.store).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Два покупателя независимо кладут весь остаток в корзины. Превышение доступного количества не меняет свою корзину или остаток. */
    @Test @AllureId("14") @DisplayName("Корзины не резервируют товар и не допускают количество выше остатка")
    fun independentCartsNeverReserve() {
        val expectedQuantity = 5
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val stock = step("Оприходуем 5 единиц товара") { Shop.supply(scope, Shop.delivery(scope, quantity = expectedQuantity)) }
        val (first, second) = step("Два покупателя независимо кладут весь остаток в свои корзины") {
            Shop.filled(scope, stock, expectedQuantity) to Shop.filled(scope, stock, expectedQuantity)
        }
        val error = step("Первый покупатель пытается увеличить количество до 6") {
            Shop.put(scope, first, stock, 6, expectedStatus = 409).`as`(ApiError::class.java)
        }
        step("Проверяем отказ и сохранение обеих корзин без резервирования") {
            error.code.shouldBe("INSUFFICIENT_STOCK")
            (Shop.getCart(scope, first)).shouldBe(first)
            (Shop.getCart(scope, second)).shouldBe(second)
            (Shop.stock(scope, stock.productId).shouldNotBeNull().availableQuantity).shouldBe(expectedQuantity)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Другой покупатель выкупает одну из двух позиций. Неудачное оформление сохраняет остаток другой позиции и исходную открытую корзину. */
    @Test @AllureId("17") @DisplayName("Нехватка одной позиции запрещает частичное списание остальных")
    fun insufficientOneLineNeverPartiallyDeducts() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val (first, second) = step("Оприходуем два разных товара по 10 единиц") { Shop.supply(scope) to Shop.supply(scope) }
        val cart = step("Первый покупатель добавляет по 3 единицы обоих товаров") {
            Shop.put(scope, Shop.filled(scope, first, 3), second, 3).`as`(Cart::class.java)
        }
        step("Другой покупатель выкупает весь второй товар") {
            Shop.submit(scope, Shop.filled(scope, second, 10))
        }
        val error = step("Пытаемся оформить исходную корзину после чужой покупки") {
            Shop.submit(scope, cart, expectedStatus = 409).`as`(ApiError::class.java)
        }
        step("Проверяем нехватку, отсутствие частичного списания и сохранение открытой корзины") {
            error.code.shouldBe("INSUFFICIENT_STOCK")
            (Shop.stock(scope, first.productId).shouldNotBeNull().availableQuantity).shouldBe(10)
            (Shop.stock(scope, second.productId).shouldNotBeNull().availableQuantity).shouldBe(0)
            (Shop.getCart(scope, cart)).shouldBe(cart)
            (SubmissionsDao.findByStoreId(scope.store).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Повтор сохраняет первоначальную версию запроса даже после закрытия корзины и возвращает ту же операцию с одним списанием. */
    @Test @AllureId("18") @DisplayName("Повтор исходного ключа возвращает принятую операцию без второго расхода")
    fun acceptedRequestReplaysOriginalVersion() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val stock = step("Оприходуем 10 единиц товара") { Shop.supply(scope) }
        val cart = step("Помещаем 3 единицы в корзину") { Shop.filled(scope, stock, 3) }
        val key = UUID.randomUUID().toString()
        val accepted = step("Принимаем заявку с сохранённым ключом") { Shop.submit(scope, cart, key).`as`(Submission::class.java) }
        val repeated = step("Повторяем исходные ключ и версию закрывшейся корзины") { Shop.submit(scope, cart, key).`as`(Submission::class.java) }
        step("Проверяем прежние идентификаторы и один расход") {
            repeated.submissionId.shouldBe(accepted.submissionId)
            repeated.eventId.shouldBe(accepted.eventId)
            (Shop.stock(scope, stock.productId).shouldNotBeNull().availableQuantity).shouldBe(7)
            (StockExpensesDao.findByStoreId(scope.store).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Принятый ключ нельзя связать с другой корзиной или версией. Оба отказа сохраняют результат первоначального оформления. */
    @Test @AllureId("19") @DisplayName("Принятый ключ нельзя использовать с другой корзиной или версией")
    fun acceptedKeyCannotChangeRequest() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val stock = step("Оприходуем товар и создаём две независимые корзины") { Shop.supply(scope) }
        val cart = step("Наполняем корзину доступным товаром") {
            Shop.filled(scope, stock, 1)
        }
        val other = step("Наполняем корзину доступным товаром") {
            Shop.filled(scope, stock, 1)
        }
        val key = UUID.randomUUID().toString()
        step("Принимаем оформление первой корзины") { Shop.submit(scope, cart, key) }
        val changedCart = step("Отправляем принятый ключ для другой корзины") { Shop.submit(scope, other, key, expectedStatus = 409).`as`(ApiError::class.java) }
        val changedVersion = step("Отправляем принятый ключ с изменённой версией первой корзины") {
            Shop.submit(scope, cart.copy(version = cart.version + 1), key, expectedStatus = 409).`as`(ApiError::class.java)
        }
        step("Проверяем конфликт параметров и отсутствие дополнительных расходов") {
            changedCart.code.shouldBe("IDEMPOTENCY_KEY_REUSED")
            changedVersion.code.shouldBe("IDEMPOTENCY_KEY_REUSED")
            (Shop.stock(scope, stock.productId).shouldNotBeNull().availableQuantity).shouldBe(9)
            (SubmissionsDao.findByStoreId(scope.store).size).shouldBe(1)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Оформленная корзина отклоняет новый расход и изменение состава, сохраняя принятый снимок. */
    @Test @AllureId("20") @DisplayName("Закрытая корзина не допускает нового оформления и изменения состава")
    fun closedCartRejectsNewExpenseAndMutation() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val stock = step("Оприходуем товар и создаём корзину") { Shop.supply(scope) }
        val cart = step("Наполняем корзину доступным товаром") {
            Shop.filled(scope, stock, 1)
        }
        step("Принимаем оформление корзины") { Shop.submit(scope, cart) }
        val expectedSnapshot = step("Получаем текущую корзину покупателя") {
            Shop.getCart(scope, cart)
        }
        val submitError = step("Пытаемся оформить закрытую корзину с новым ключом") { Shop.submit(scope, expectedSnapshot, expectedStatus = 409).`as`(ApiError::class.java) }
        val editError = step("Пытаемся изменить состав закрытой корзины") { Shop.put(scope, expectedSnapshot, stock, 2, expectedStatus = 409).`as`(ApiError::class.java) }
        step("Проверяем закрытое состояние и неизменность принятого состава") {
            submitError.code.shouldBe("CART_ALREADY_SUBMITTED")
            editError.code.shouldBe("CART_ALREADY_SUBMITTED")
            (Shop.getCart(scope, cart)).shouldBe(expectedSnapshot)
            (Shop.stock(scope, stock.productId).shouldNotBeNull().availableQuantity).shouldBe(9)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Изменение корзины делает запрос со старой версией недействительным. Остаток сохраняется, исходящее сообщение не создаётся. */
    @Test @AllureId("22") @DisplayName("Устаревшая версия корзины не может списать товар")
    fun staleVersionCannotPurchase() {
        val scope = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val stock = step("Оприходуем товар и создаём корзину с одной единицей") { Shop.supply(scope) }
        val original = step("Наполняем корзину доступным товаром") {
            Shop.filled(scope, stock, 1)
        }
        val expectedCart = step("Меняем количество в корзине на 2") { Shop.put(scope, original, stock, 2).`as`(Cart::class.java) }
        val error = step("Оформляем заявку с сохранённой устаревшей версией") { Shop.submit(scope, original, expectedStatus = 409).`as`(ApiError::class.java) }
        step("Проверяем конфликт версии и сохранение последнего состава без расхода") {
            error.code.shouldBe("CART_VERSION_CONFLICT")
            (Shop.getCart(scope, original)).shouldBe(expectedCart)
            (Shop.stock(scope, stock.productId).shouldNotBeNull().availableQuantity).shouldBe(10)
            (StoreOutboxDao.findByStoreId(scope.store).size).shouldBe(0)
            InventoryDao.findByStoreId(scope.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(scope.store).isEmpty()).shouldBeTrue() }
        }
    }

    /** Уникальность ключа ограничена магазином. Чужие корзины, остатки и заявки недоступны за пределами своего магазина. */
    @Test @AllureId("30") @DisplayName("Ключи и ресурсы двух магазинов имеют независимые области видимости")
    fun storeScopesSeparateKeysAndResources() {
        val first = step("Создаём магазин для поставки и покупок") {
            Shop.scope()
        }
        val second = step("Создаём магазин для поставки и покупок") {
            Shop.scope("SPB")
        }
        val (firstStock, secondStock) = step("Оприходуем товары в Москве и Санкт-Петербурге") { Shop.supply(first) to Shop.supply(second) }
        val firstCart = step("Наполняем корзину доступным товаром") {
            Shop.filled(first, firstStock, 1)
        }
        val secondCart = step("Наполняем корзину доступным товаром") {
            Shop.filled(second, secondStock, 1)
        }
        val key = UUID.randomUUID().toString()
        val (acceptedFirst, acceptedSecond) = step("Оформляем две заявки с одинаковой строкой ключа в разных магазинах") {
            Shop.submit(first, firstCart, key).`as`(Submission::class.java) to Shop.submit(second, secondCart, key).`as`(Submission::class.java)
        }
        step("Проверяем независимость операций, тарифов и недоступность чужих ресурсов") {
            acceptedSecond.submissionId.shouldNotBe(acceptedFirst.submissionId)
            secondStock.unitPrice.shouldBe("121.00")
            storeService.getCart(second.store, firstCart.cartId, expectedStatus = 404)
            Shop.put(second, Shop.cart(second), firstStock, 1, expectedStatus = 404)
            storeService.getSubmission(second.store, acceptedFirst.submissionId, expectedStatus = 404)
            InventoryDao.findByStoreId(first.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(first.store).isEmpty()).shouldBeTrue() }
            InventoryDao.findByStoreId(second.store).forEach { row ->
                val received = StockMovementsDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                val spent = StockExpensesDao.findByStockItemId(row.stockItemId).sumOf { it.quantity }
                withClue("Баланс продукта ${row.productId}") { row.availableQuantity.shouldBe(received - spent) }
            }
            withClue("У принятой заявки должен быть outbox") { (SubmissionsDao.findIdsWithoutOutboxByStoreId(second.store).isEmpty()).shouldBeTrue() }
        }
    }


}
