package org.golenev.tests.backend

import org.golenev.db.tables.storeOutbox.StoreOutboxDao
import org.golenev.db.tables.submissions.SubmissionsDao
import org.golenev.db.tables.stockExpenses.StockExpensesDao
import org.golenev.restapi.endpoints.*
import org.golenev.utils.*
import org.golenev.commondto.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** Инварианты оформления после настоящей поставки. Каждый тест управляет своими ресурсами через функцию с постфиксом Template. */
@DisplayName("Оформление заявки и независимые корзины")
class OrderE2ETest {
    private val storeService = StoreServiceDao()

    /** На остатке десять единиц, в корзине три. Оформление атомарно списывает товар, фиксирует состав и публикует одно событие заявки. */
    @Test @AllureId("16") @DisplayName("Принятая заявка атомарно списывает товар и передаёт неизменяемый состав")
    fun acceptancePersistsExactSnapshotAndEvent() {
        withShopTemplate { resources ->
            val expectedRemaining = 7
            val expectedTotal = "360.00"
            val scope = resources.scope()
            val (stock, cart) = step("Оприходуем 10 единиц и помещаем 3 в независимую корзину") {
                val stock = Shop.supply(scope, Shop.delivery(scope, quantity = 10, price = "100.00"))
                stock to Shop.filled(scope, stock, 3)
            }
            val observer = resources.observe("store.order-submitted", scope.store)
            val accepted = step("Оформляем заявку по актуальной версии корзины") {
                Shop.submit(scope, cart).expect(202).body<Submission>()
            }
            step("Проверяем единственный расход, закрытую корзину и состав переданной заявки") {
                val published = Shop.published(scope, accepted)
                assertEquals(accepted.submissionId, published.submissionId)
                assertNotNull(published.publishedAt)
                assertEquals(expectedRemaining, Shop.requireStock(scope, stock.productId).availableQuantity)
                val closed = Shop.getCart(scope, cart)
                assertEquals(CartState.SUBMITTED, closed.state)
                assertEquals(expectedTotal, closed.totalAmount)
                assertEquals(cart.version + 1, closed.version)
                assertEquals(accepted.submissionId, closed.submissionId)
                val event = observer.exactly(accepted.eventId, 1).single().body<OrderSubmitted>()
                assertEquals("OrderSubmitted", event.eventType)
                assertEquals(scope.store, event.storeId)
                assertEquals(accepted.submissionId, event.payload.submissionId)
                assertEquals(cart.cartId, event.payload.cartId)
                assertEquals(expectedTotal, event.payload.totalAmount)
                assertEquals(3, event.payload.items.single().quantity)
                assertEquals("120.00", event.payload.items.single().unitPrice)
                assertEquals("1", StockExpensesDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Два покупателя независимо кладут весь остаток в корзины. Превышение доступного количества не меняет свою корзину или остаток. */
    @Test @AllureId("14") @DisplayName("Корзины не резервируют товар и не допускают количество выше остатка")
    fun independentCartsNeverReserve() {
        withShopTemplate { resources ->
            val expectedQuantity = 5
            val scope = resources.scope()
            val stock = step("Оприходуем 5 единиц товара") { Shop.supply(scope, Shop.delivery(scope, quantity = expectedQuantity)) }
            val (first, second) = step("Два покупателя независимо кладут весь остаток в свои корзины") {
                Shop.filled(scope, stock, expectedQuantity) to Shop.filled(scope, stock, expectedQuantity)
            }
            val error = step("Первый покупатель пытается увеличить количество до 6") {
                Shop.put(scope, first, stock, 6).expect(409).body<ApiError>()
            }
            step("Проверяем отказ и сохранение обеих корзин без резервирования") {
                assertEquals("INSUFFICIENT_STOCK", error.code)
                assertEquals(first, Shop.getCart(scope, first))
                assertEquals(second, Shop.getCart(scope, second))
                assertEquals(expectedQuantity, Shop.requireStock(scope, stock.productId).availableQuantity)
                Shop.audit(scope)
            }
        }
    }

    /** Два изменения одной версии корзины имеют одного победителя. Затем независимо проверяются его количество и следующая версия. */
    @Test @AllureId("15") @DisplayName("Конкурентные изменения одной версии корзины имеют одного победителя")
    fun competingCartEditsHaveOneVersionWinner() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val (stock, cart) = step("Создаём пустую корзину для доступного товара") { Shop.supply(scope) to Shop.cart(scope) }
            val outcomes = step("Одновременно заменяем количество на 2 и 3 с одной исходной версией") {
                Shop.race({ Shop.put(scope, cart, stock, 2) }, { Shop.put(scope, cart, stock, 3) })
            }
            step("Проверяем единственное изменение версии и количество победившего запроса") {
                assertEquals(listOf(200, 409), outcomes.map { it.status }.sorted())
                val expectedQuantity = if (outcomes[0].status == 200) 2 else 3
                val loser = outcomes.single { it.status == 409 }.body<ApiError>()
                assertEquals("CART_VERSION_CONFLICT", loser.code)
                val actual = Shop.getCart(scope, cart)
                assertEquals(cart.cartId, actual.cartId)
                assertEquals(cart.version + 1, actual.version)
                assertEquals(expectedQuantity, actual.items.single().quantity)
                Shop.audit(scope)
            }
        }
    }

    /** Другой покупатель выкупает одну из двух позиций. Неудачное оформление сохраняет остаток другой позиции и исходную открытую корзину. */
    @Test @AllureId("17") @DisplayName("Нехватка одной позиции запрещает частичное списание остальных")
    fun insufficientOneLineNeverPartiallyDeducts() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val (first, second) = step("Оприходуем два разных товара по 10 единиц") { Shop.supply(scope) to Shop.supply(scope) }
            val cart = step("Первый покупатель добавляет по 3 единицы обоих товаров") {
                Shop.put(scope, Shop.filled(scope, first, 3), second, 3).expect(200).body<Cart>()
            }
            step("Другой покупатель выкупает весь второй товар") {
                Shop.submit(scope, Shop.filled(scope, second, 10)).expect(202)
            }
            val error = step("Пытаемся оформить исходную корзину после чужой покупки") {
                Shop.submit(scope, cart).expect(409).body<ApiError>()
            }
            step("Проверяем нехватку, отсутствие частичного списания и сохранение открытой корзины") {
                assertEquals("INSUFFICIENT_STOCK", error.code)
                assertEquals(10, Shop.requireStock(scope, first.productId).availableQuantity)
                assertEquals(0, Shop.requireStock(scope, second.productId).availableQuantity)
                assertEquals(cart, Shop.getCart(scope, cart))
                assertEquals("1", SubmissionsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Повтор сохраняет первоначальную версию запроса даже после закрытия корзины и возвращает ту же операцию с одним списанием. */
    @Test @AllureId("18") @DisplayName("Повтор исходного ключа возвращает принятую операцию без второго расхода")
    fun acceptedRequestReplaysOriginalVersion() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем 10 единиц товара") { Shop.supply(scope) }
            val cart = step("Помещаем 3 единицы в корзину") { Shop.filled(scope, stock, 3) }
            val key = UUID.randomUUID().toString()
            val accepted = step("Принимаем заявку с сохранённым ключом") { Shop.submit(scope, cart, key).expect(202).body<Submission>() }
            val repeated = step("Повторяем исходные ключ и версию закрывшейся корзины") { Shop.submit(scope, cart, key).expect(202).body<Submission>() }
            step("Проверяем прежние идентификаторы и один расход") {
                assertEquals(accepted.submissionId, repeated.submissionId)
                assertEquals(accepted.eventId, repeated.eventId)
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("1", StockExpensesDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Одновременные одинаковые запросы проверяют реальную гонку транзакций и ограничения уникальности. Результат — одна принятая операция. */
    @Test @AllureId("181") @DisplayName("Одновременные повторы одного ключа создают один расход")
    fun simultaneousSameKeyCreatesOneExpense() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем 10 единиц товара") { Shop.supply(scope) }
            val cart = step("Создаём корзину с 3 единицами") { Shop.filled(scope, stock, 3) }
            val key = UUID.randomUUID().toString()
            val replies = step("Отправляем два одинаковых оформления одновременно") {
                Shop.race({ Shop.submit(scope, cart, key) }, { Shop.submit(scope, cart, key) })
            }
            step("Проверяем одинаковые операции и единственное списание") {
                val first = replies[0].expect(202).body<Submission>()
                val second = replies[1].expect(202).body<Submission>()
                assertEquals(first.submissionId, second.submissionId)
                assertEquals(first.eventId, second.eventId)
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("1", StockExpensesDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Принятый ключ нельзя связать с другой корзиной или версией. Оба отказа сохраняют результат первоначального оформления. */
    @Test @AllureId("19") @DisplayName("Принятый ключ нельзя использовать с другой корзиной или версией")
    fun acceptedKeyCannotChangeRequest() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и создаём две независимые корзины") { Shop.supply(scope) }
            val cart = Shop.filled(scope, stock, 1)
            val other = Shop.filled(scope, stock, 1)
            val key = UUID.randomUUID().toString()
            step("Принимаем оформление первой корзины") { Shop.submit(scope, cart, key).expect(202) }
            val changedCart = step("Отправляем принятый ключ для другой корзины") { Shop.submit(scope, other, key).expect(409).body<ApiError>() }
            val changedVersion = step("Отправляем принятый ключ с изменённой версией первой корзины") {
                Shop.submit(scope, cart.copy(version = cart.version + 1), key).expect(409).body<ApiError>()
            }
            step("Проверяем конфликт параметров и отсутствие дополнительных расходов") {
                assertEquals("IDEMPOTENCY_KEY_REUSED", changedCart.code)
                assertEquals("IDEMPOTENCY_KEY_REUSED", changedVersion.code)
                assertEquals(9, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("1", SubmissionsDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Оформленная корзина отклоняет новый расход и изменение состава, сохраняя принятый снимок. */
    @Test @AllureId("20") @DisplayName("Закрытая корзина не допускает нового оформления и изменения состава")
    fun closedCartRejectsNewExpenseAndMutation() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и создаём корзину") { Shop.supply(scope) }
            val cart = Shop.filled(scope, stock, 1)
            step("Принимаем оформление корзины") { Shop.submit(scope, cart).expect(202) }
            val expectedSnapshot = Shop.getCart(scope, cart)
            val submitError = step("Пытаемся оформить закрытую корзину с новым ключом") { Shop.submit(scope, expectedSnapshot).expect(409).body<ApiError>() }
            val editError = step("Пытаемся изменить состав закрытой корзины") { Shop.put(scope, expectedSnapshot, stock, 2).expect(409).body<ApiError>() }
            step("Проверяем закрытое состояние и неизменность принятого состава") {
                assertEquals("CART_ALREADY_SUBMITTED", submitError.code)
                assertEquals("CART_ALREADY_SUBMITTED", editError.code)
                assertEquals(expectedSnapshot, Shop.getCart(scope, cart))
                assertEquals(9, Shop.requireStock(scope, stock.productId).availableQuantity)
                Shop.audit(scope)
            }
        }
    }

    /** Два покупателя одновременно оформляют последнюю единицу. Побеждает один, второй получает отказ из-за недостатка товара. */
    @Test @AllureId("21") @DisplayName("Последнюю единицу товара выкупает один из двух конкурирующих покупателей")
    fun lastUnitHasOneWinner() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем последнюю единицу товара") { Shop.supply(scope, Shop.delivery(scope, quantity = 1)) }
            val (first, second) = step("Два покупателя кладут последнюю единицу в разные корзины") { Shop.filled(scope, stock, 1) to Shop.filled(scope, stock, 1) }
            val replies = step("Оформляем обе корзины одновременно") { Shop.race({ Shop.submit(scope, first) }, { Shop.submit(scope, second) }) }
            step("Проверяем одного победителя, нехватку у проигравшего и нулевой остаток") {
                assertEquals(listOf(202, 409), replies.map { it.status }.sorted())
                assertEquals("INSUFFICIENT_STOCK", replies.single { it.status == 409 }.body<ApiError>().code)
                assertEquals(0, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("1", StockExpensesDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Изменение корзины делает запрос со старой версией недействительным. Остаток сохраняется, исходящее сообщение не создаётся. */
    @Test @AllureId("22") @DisplayName("Устаревшая версия корзины не может списать товар")
    fun staleVersionCannotPurchase() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и создаём корзину с одной единицей") { Shop.supply(scope) }
            val original = Shop.filled(scope, stock, 1)
            val expectedCart = step("Меняем количество в корзине на 2") { Shop.put(scope, original, stock, 2).expect(200).body<Cart>() }
            val error = step("Оформляем заявку с сохранённой устаревшей версией") { Shop.submit(scope, original).expect(409).body<ApiError>() }
            step("Проверяем конфликт версии и сохранение последнего состава без расхода") {
                assertEquals("CART_VERSION_CONFLICT", error.code)
                assertEquals(expectedCart, Shop.getCart(scope, original))
                assertEquals(10, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("0", StoreOutboxDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
        }
    }

    /** Уникальность ключа ограничена магазином. Чужие корзины, остатки и заявки недоступны за пределами своего магазина. */
    @Test @AllureId("30") @DisplayName("Ключи и ресурсы двух магазинов имеют независимые области видимости")
    fun storeScopesSeparateKeysAndResources() {
        withShopTemplate { resources ->
            val first = resources.scope()
            val second = resources.scope("SPB")
            val (firstStock, secondStock) = step("Оприходуем товары в Москве и Санкт-Петербурге") { Shop.supply(first) to Shop.supply(second) }
            val firstCart = Shop.filled(first, firstStock, 1)
            val secondCart = Shop.filled(second, secondStock, 1)
            val key = UUID.randomUUID().toString()
            val (acceptedFirst, acceptedSecond) = step("Оформляем две заявки с одинаковой строкой ключа в разных магазинах") {
                Shop.submit(first, firstCart, key).expect(202).body<Submission>() to Shop.submit(second, secondCart, key).expect(202).body<Submission>()
            }
            step("Проверяем независимость операций, тарифов и недоступность чужих ресурсов") {
                assertNotEquals(acceptedFirst.submissionId, acceptedSecond.submissionId)
                assertEquals("121.00", secondStock.unitPrice)
                storeService.getCart(second.store, firstCart.cartId).expect(404)
                Shop.put(second, Shop.cart(second), firstStock, 1).expect(404)
                storeService.getSubmission(second.store, acceptedFirst.submissionId).expect(404)
                Shop.audit(first)
                Shop.audit(second)
            }
        }
    }

    /** Управляемая SQL-ошибка после изменения остатка откатывает все записи оформления. После восстановления исходный ключ позволяет оформить заявку. */
    @Test @AllureId("24") @DisplayName("Ошибка записи полностью откатывает принятие заявки")
    fun commitFailureRollsBackWholeAcceptance() {
        withShopTemplate { resources ->
            val scope = resources.scope()
            val stock = step("Оприходуем товар и готовим корзину с 3 единицами") { Shop.supply(scope) }
            val cart = Shop.filled(scope, stock, 3)
            val key = UUID.randomUUID().toString()
            step("Воспроизводим ошибку записи расхода только для этого покупателя") { resources.failExpense(scope) }
            val error = step("Оформляем заявку при недоступной записи расхода") { Shop.submit(scope, cart, key).expect(503).body<ApiError>() }
            step("Проверяем полный откат остатка, корзины и исходящего сообщения") {
                assertEquals("DEPENDENCY_UNAVAILABLE", error.code)
                assertEquals(cart, Shop.getCart(scope, cart))
                assertEquals(10, Shop.requireStock(scope, stock.productId).availableQuantity)
                assertEquals("0", SubmissionsDao.countByStoreId(scope.store).toString())
                assertEquals("0", StoreOutboxDao.countByStoreId(scope.store).toString())
                Shop.audit(scope)
            }
            step("Восстанавливаем запись и повторяем исходный запрос") {
                Shop.release("store", scope)
                Shop.submit(scope, cart, key).expect(202)
            }
            step("Проверяем единственный расход после восстановления") {
                assertEquals(7, Shop.requireStock(scope, stock.productId).availableQuantity)
                Shop.audit(scope)
            }
        }
    }
}
