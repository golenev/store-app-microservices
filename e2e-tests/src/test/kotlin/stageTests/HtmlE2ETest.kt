package stageTests

import config.HttpClient
import constants.Endpoints
import helpers.*
import models.Cart
import models.SubmitCart
import models.DeliveryReceived
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import ui.*
import com.fasterxml.jackson.module.kotlin.readValue
import required

/** Real HTML E2E: Kotlin/Selenide windows, isolated relay faults and the actual supplier/Kafka/pricing/STORE chain. */
@Epic("Учебный магазин")
@Feature("HTML: каталог, корзина и поставщик")
class HtmlE2ETest {
    /** Supplies three units; two profiles hold all three; first submits and second receives insufficiency without reservation. */
    @Test @DisplayName("Независимые браузеры не резервируют остаток; оформление проверяет его повторно")
    fun independentCarts() {
        withBrowserTemplate { ui ->
            val first = ui.window()
            val second = ui.window()
            val product = ui.product()
            step("Поставляем три единицы через HTML и ожидаем реальный приход") {
                first.supplier.open()
                first.supplier.fill(product, 3, "100.00", "Общий остаток", "Две независимые корзины")
                first.supplier.send()
                first.supplier.checkPosted()
                ui.stock(product, 3)
            }
            step("Оба покупателя кладут весь остаток в свои корзины") {
                first.catalog.open()
                second.catalog.open()
                first.catalog.add(product, 3)
                second.catalog.add(product, 3)
                assertNotEquals(first.catalog.cartId(), second.catalog.cartId())
                assertEquals(3, Shop.requireStock(ui.scope, product).availableQuantity)
            }
            step("Первый покупатель оформляет; второй видит недостаток товара") {
                first.catalog.submit()
                first.catalog.checkPublished()
                second.catalog.submit()
                second.catalog.checkError("Товара уже недостаточно")
                second.catalog.checkLine("3 × 120.00")
                assertEquals(0, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 3)
            }
        }
    }

    /** Replenishes an open cart, accepts at the new price, then verifies another receipt cannot rewrite its accepted total. */
    @Test @DisplayName("Открытая корзина обновляет цену, принятая заявка сохраняет снимок")
    fun priceSnapshot() {
        withBrowserTemplate { ui ->
            val buyer = ui.window()
            val supplier = ui.window()
            val product = ui.product()
            step("Поставляем товар и добавляем две единицы в открытую корзину") {
                supplier.supplier.open()
                supplier.supplier.fill(product, 5, "100.00", "Меняющаяся цена", "Снимок оформления")
                supplier.supplier.send()
                supplier.supplier.checkPosted()
                ui.stock(product, 5)
                buyer.catalog.open()
                buyer.catalog.add(product, 2)
                buyer.catalog.checkTotal("240.00")
            }
            step("Новая поставка переоценивает открытый остаток; принимаем заявку на 480 рублей") {
                supplier.supplier.newDelivery()
                supplier.supplier.fill(product, 5, "200.00", "Меняющаяся цена", "Снимок оформления")
                supplier.supplier.send()
                supplier.supplier.checkPosted()
                ui.stock(product, 10)
                buyer.catalog.refresh()
                buyer.catalog.checkTotal("480.00")
                buyer.catalog.submit()
                buyer.catalog.checkPublished()
            }
            step("Следующая поставка меняет каталог; принятая корзина остаётся на 480 рублей") {
                supplier.supplier.newDelivery()
                supplier.supplier.fill(product, 1, "300.00", "Меняющаяся цена", "Снимок оформления")
                supplier.supplier.send()
                supplier.supplier.checkPosted()
                assertEquals("360.00", ui.stock(product, 9).unitPrice)
                buyer.catalog.refresh()
                buyer.catalog.checkTotal("480.00")
                ui.checkExpense(product, 1, 2)
            }
        }
    }

    /** Drops the committed acceptance reply; reload reuses the original version/key and causes one logical expense. */
    @Test @DisplayName("Потерянный ответ оформления: перезагрузка повторяет исходный запрос")
    fun lostReply() {
        withBrowserTemplate { ui ->
            val (buyer, product) = preparedCart(ui)
            val path = "/stores/S-1/carts/${buyer.catalog.cartId()}/submit"
            step("Теряем ответ после принятия заявки") {
                ui.proxy.loseReplyAfterCommit(path)
                buyer.catalog.submit()
                buyer.catalog.checkUnknownOutcome()
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            step("Снимаем сбой и перезагружаем ту же вкладку") {
                ui.proxy.releaseFault()
                buyer.catalog.reload()
                buyer.catalog.checkPublished()
                checkReplay(ui.proxy, path, original, originalCount)
                ui.checkExpense(product, 1, 1)
            }
        }
    }

    /** Substitutes 503 after real acceptance; explicit retry keeps the original request and expense remains singular. */
    @Test @DisplayName("503 после принятия: кнопка повтора сохраняет ключ и тело запроса")
    fun ambiguous503() {
        withBrowserTemplate { ui ->
            val (buyer, product) = preparedCart(ui)
            val path = "/stores/S-1/carts/${buyer.catalog.cartId()}/submit"
            step("Принимаем заявку, но показываем браузеру инфраструктурный 503") {
                ui.proxy.rejectReplyAfterCommit(path)
                buyer.catalog.submit()
                buyer.catalog.checkRetryAvailable()
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            step("Повторяем оформление кнопкой после снятия сбоя") {
                ui.proxy.releaseFault()
                buyer.catalog.retry()
                buyer.catalog.checkPublished()
                checkReplay(ui.proxy, path, original, originalCount)
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 1)
            }
        }
    }

    /** Drops acceptance before forwarding; reload reuses the same request, then commits exactly one expense. */
    @Test @DisplayName("Потерянный запрос до принятия: перезагрузка оформляет один раз с исходным ключом")
    fun lostRequest() {
        withBrowserTemplate { ui ->
            val (buyer, product) = preparedCart(ui)
            val path = "/stores/S-1/carts/${buyer.catalog.cartId()}/submit"
            step("Теряем запрос до STORE; товар ещё не списан") {
                ui.proxy.loseRequestBeforeCommit(path)
                buyer.catalog.submit()
                buyer.catalog.checkUnknownOutcome()
                assertEquals(5, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 0, 0)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            step("Перезагружаем вкладку после снятия сбоя; принимаем исходную операцию") {
                ui.proxy.releaseFault()
                buyer.catalog.reload()
                buyer.catalog.checkPublished()
                checkReplay(ui.proxy, path, original, originalCount)
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 1)
            }
        }
    }

    /** Edits the same cart externally after UI initialization; stale submit refreshes composition before a new acceptance. */
    @Test @DisplayName("Конфликт версии обновляет корзину перед следующим оформлением")
    fun versionConflict() {
        withBrowserTemplate { ui ->
            val (buyer, product) = preparedCart(ui)
            val cart = HttpClient.request(Endpoints.STORE, "/stores/S-1/carts/${buyer.catalog.cartId()}").expect(200).body<Cart>()
            step("Другой клиент меняет количество в той же корзине с текущей версией") {
                Shop.put(ui.scope, cart, Shop.requireStock(ui.scope, product), 2).expect(200)
            }
            step("Устаревшее оформление отклоняется и UI получает актуальную сумму") {
                buyer.catalog.submit()
                buyer.catalog.checkError("Корзина изменилась")
                buyer.catalog.checkTotal("240.00")
                assertEquals(5, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 0, 0)
            }
            step("Повторное оформление использует обновлённую корзину") {
                buyer.catalog.submit()
                buyer.catalog.checkPublished()
                assertEquals(3, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 2)
            }
        }
    }

    /** Supplies script-like name and description; actual browser renders literal text without executing or creating nodes. */
    @Test @DisplayName("Название и описание товара отображаются текстом без исполнения HTML")
    fun safeRendering() {
        withBrowserTemplate { ui ->
            val browser = ui.window()
            val product = ui.product()
            val name = "<img src=x onerror=\"window.compromised=true\">"
            val description = "<script>window.compromised=true</script>"
            step("Поставляем товар с недоверенным текстом") {
                browser.supplier.open()
                browser.supplier.fill(product, 5, "100.00", name, description)
                browser.supplier.send()
                browser.supplier.checkPosted()
                ui.stock(product, 5)
            }
            step("Открываем каталог и проверяем текст, DOM и отсутствие исполнения") {
                browser.catalog.open()
                browser.catalog.checkLiteralProduct(product, name, description)
            }
        }
    }

    /** Supplies price beyond JS integer precision; server-calculated total is rendered as the exact decimal string. */
    @Test @DisplayName("Большая денежная сумма отображается без потери точности")
    fun exactLargeMoney() {
        withBrowserTemplate { ui ->
            val browser = ui.window()
            val product = ui.product()
            step("Поставляем товар с закупочной ценой выше точности JavaScript Number") {
                browser.supplier.open()
                browser.supplier.fill(product, 5, "9007199254740993.00", "Большая сумма", "Десятичные деньги")
                browser.supplier.send()
                browser.supplier.checkPosted()
                assertEquals("11709359031163290.90", ui.stock(product, 5).unitPrice)
            }
            step("Добавляем две единицы и проверяем точную серверную сумму") {
                browser.catalog.open()
                browser.catalog.add(product, 2, "11709359031163290.90")
                browser.catalog.checkTotal("23418718062326581.80")
            }
        }
    }

    /** Loses supplier reply after broker acknowledgement; reload preserves the full event envelope and one logical receipt. */
    @Test @DisplayName("Перезагрузка поставщика сохраняет envelope и не удваивает приход")
    fun supplierRetry() {
        withBrowserTemplate { ui ->
            val browser = ui.window()
            val product = ui.product()
            val path = "/warehouse/technical/deliveries"
            step("Теряем ответ поставщику после реальной публикации") {
                browser.supplier.open()
                browser.supplier.fill(product, 7, "100.00", "Повтор поставки", "Неизменяемый envelope")
                ui.proxy.loseReplyAfterCommit(path)
                browser.supplier.send()
                browser.supplier.checkUnknownOutcome()
                ui.stock(product, 7)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            val envelope = HttpClient.mapper.readValue<DeliveryReceived>(original.body)
            step("Снимаем сбой и перезагружаем форму; повторяем тот же envelope") {
                ui.proxy.releaseFault()
                browser.supplier.reload()
                browser.supplier.checkPosted()
                val replay = oneReplay(ui.proxy, path, originalCount)
                assertEquals(original.body, replay.body, "Full immutable supplier envelope")
                awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
                assertEquals(7, Shop.requireStock(ui.scope, product).availableQuantity)
                assertEquals("1", config.Database.scalar("store", "SELECT count(*) FROM stock_receipts WHERE store_id=? AND delivery_id=?", "S-1", envelope.payload.deliveryId))
            }
        }
    }

    /** Switches S-1/S-2 in one profile; second store is empty and switching back restores the first cart's line. */
    @Test @DisplayName("Переключение магазина сохраняет независимую корзину каждого магазина")
    fun storesAndPages() {
        withBrowserTemplate { ui ->
            val (buyer, _) = preparedCart(ui)
            val firstCart = buyer.catalog.cartId()
            step("Переключаемся в другой магазин с пустой корзиной") {
                buyer.catalog.selectStore("S-2")
                buyer.catalog.checkEmpty()
                assertNotEquals(firstCart, buyer.catalog.cartId("S-2"))
            }
            step("Возвращаемся в первый магазин и видим исходную корзину") {
                buyer.catalog.selectStore("S-1")
                buyer.catalog.checkLine("1 × 120.00")
                assertEquals(firstCart, buyer.catalog.cartId())
            }
        }
    }

    /** Navigates all preserved HTML entry points; the browser calls scoped APIs without removed auth/global routes. */
    @Test @DisplayName("Сохранённые HTML-страницы используют новые API без авторизации")
    fun preservedPages() {
        withBrowserTemplate { ui ->
            val browser = ui.window()
            step("Открываем сохранённые страницы и рабочий каталог") {
                browser.navigation.open("/index.html")
                browser.navigation.open("/login.html")
                browser.catalog.open()
            }
            step("Проверяем фактические запросы страниц") {
                val requests = ui.proxy.allRequests()
                assertTrue(requests.any { it.path.matches(Regex("/stores/S-1/carts/[a-f0-9-]+")) })
                for (request in requests) {
                    assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) }, request.path)
                    assertFalse(Regex("^/(cart|order|products|login|auth)(/|$)").containsMatchIn(request.path), request.path)
                }
            }
        }
    }

    /** Renders a populated cart at a declared 390-pixel CSS viewport and checks horizontal overflow. */
    @Test @DisplayName("Мобильная корзина помещается в viewport 390 пикселей")
    fun mobileLayout() {
        withBrowserTemplate { ui ->
            val (buyer, _) = preparedCart(ui, mobile = true)
            step("Проверяем ширину наполненной мобильной корзины") {
                buyer.catalog.checkViewportWidth(390)
                buyer.catalog.checkFitsViewport()
            }
        }
    }

    /** Common valid setup only: real HTML supply, five received units and one UI cart line; no scenario expectation is hidden. */
    private fun preparedCart(ui: BrowserScenario, mobile: Boolean = false): Pair<BrowserWindow, String> {
        val browser = ui.window(mobile)
        val product = ui.product()
        step("Поставляем пять единиц через HTML и наполняем свою корзину одной единицей") {
            browser.supplier.open()
            browser.supplier.fill(product, 5, "100.00", "Тестовый товар", "Подготовка независимой корзины")
            browser.supplier.send()
            browser.supplier.checkPosted()
            ui.stock(product, 5)
            browser.catalog.open()
            browser.catalog.add(product, 1)
            browser.catalog.checkTotal("120.00")
        }
        return browser to product
    }

    /** Captures original physical attempts before retry; Chrome may retry a dropped connection, but every attempt must keep one identity. */
    private fun originalRequests(proxy: BrowserProxy, path: String): Pair<BrowserRequest, Int> {
        val requests = proxy.requests(path)
        assertTrue(requests.isNotEmpty(), "Original browser request: $path")
        val original = requests[0]
        for (request in requests) {
            assertEquals(original.key, request.key, "Automatic transport retry keeps key")
            assertEquals(original.body, request.body, "Automatic transport retry keeps body")
        }
        return original to requests.size
    }

    /** Requires one explicit replay after the independently captured original attempt count; physical transport attempts stay visible. */
    private fun oneReplay(proxy: BrowserProxy, path: String, originalCount: Int): BrowserRequest {
        val requests = proxy.requests(path)
        assertEquals(originalCount + 1, requests.size, "Original physical attempts plus explicit replay: $path")
        return requests[originalCount]
    }

    /** Checks replay key/body against the pre-retry snapshot and independently requires the original cart version one. */
    private fun checkReplay(proxy: BrowserProxy, path: String, original: BrowserRequest, originalCount: Int) {
        val replay = oneReplay(proxy, path, originalCount)
        assertFalse(required(original.key, "original Idempotency-Key").isBlank())
        assertEquals(SubmitCart(1), HttpClient.mapper.readValue<SubmitCart>(original.body), "Original version before acceptance")
        assertEquals(original.key, replay.key, "Original operation key survives retry")
        assertEquals(original.body, replay.body, "Original request survives retry")
    }
}
