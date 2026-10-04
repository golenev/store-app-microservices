package org.golenev.tests.e2e_tests

import org.golenev.utils.JsonUtils
import org.golenev.restapi.endpoints.*
import org.golenev.utils.*
import org.golenev.commondto.Cart
import org.golenev.commondto.SubmitCart
import org.golenev.commondto.DeliveryReceived
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.golenev.ui.config.*
import org.golenev.ui.pages.*
import com.codeborne.selenide.WebDriverRunner.setWebDriver
import com.fasterxml.jackson.module.kotlin.readValue
import org.golenev.utils.required

/** Сквозные проверки HTML через Kotlin и Selenide: собственные браузеры, управляемые сетевые сбои и настоящая цепочка поставки до STORE. */
@Epic("Учебный магазин")
@Feature("HTML: каталог, корзина и поставщик")
class HtmlE2ETest {
    private val storeService = StoreServiceDao()

    /** После поставки трёх единиц два браузера кладут весь остаток в свои корзины. Первое оформление проходит, второе получает отказ без предварительного резерва. */
    @Test @DisplayName("Независимые браузеры не резервируют остаток; оформление проверяет его повторно")
    fun independentCarts() {
        withBrowserTemplate { ui ->
            val first = ui.browser()
            val second = ui.browser()
            val product = ui.product()
            step("Поставляем три единицы через HTML и ожидаем реальный приход") {
                setWebDriver(first)
                supplierPage.open()
                supplierPage.fill(product, 3, "100.00", "Общий остаток", "Две независимые корзины")
                supplierPage.send()
                supplierPage.checkPosted()
                ui.stock(product, 3)
            }
            step("Оба покупателя кладут весь остаток в свои корзины") {
                setWebDriver(first)
                catalogPage.open()
                catalogPage.add(product, 3)
                val firstCart = catalogPage.cartId()
                setWebDriver(second)
                catalogPage.open()
                catalogPage.add(product, 3)
                assertNotEquals(firstCart, catalogPage.cartId())
                assertEquals(3, Shop.requireStock(ui.scope, product).availableQuantity)
            }
            step("Первый покупатель оформляет; второй видит недостаток товара") {
                setWebDriver(first)
                catalogPage.submit()
                catalogPage.checkPublished()
                setWebDriver(second)
                catalogPage.submit()
                catalogPage.checkError("Товара уже недостаточно")
                catalogPage.checkLine("3 × 120.00")
                assertEquals(0, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 3)
            }
        }
    }

    /** Новая поставка меняет цену открытой корзины. После оформления ещё одна поставка не должна изменить принятую сумму. */
    @Test @DisplayName("Открытая корзина обновляет цену, принятая заявка сохраняет снимок")
    fun priceSnapshot() {
        withBrowserTemplate { ui ->
            val buyer = ui.browser()
            val supplier = ui.browser()
            val product = ui.product()
            step("Поставляем товар и добавляем две единицы в открытую корзину") {
                setWebDriver(supplier)
                supplierPage.open()
                supplierPage.fill(product, 5, "100.00", "Меняющаяся цена", "Снимок оформления")
                supplierPage.send()
                supplierPage.checkPosted()
                ui.stock(product, 5)
                setWebDriver(buyer)
                catalogPage.open()
                catalogPage.add(product, 2)
                catalogPage.checkTotal("240.00")
            }
            step("Новая поставка переоценивает открытый остаток; принимаем заявку на 480 рублей") {
                setWebDriver(supplier)
                supplierPage.newDelivery()
                supplierPage.fill(product, 5, "200.00", "Меняющаяся цена", "Снимок оформления")
                supplierPage.send()
                supplierPage.checkPosted()
                ui.stock(product, 10)
                setWebDriver(buyer)
                catalogPage.refresh()
                catalogPage.checkTotal("480.00")
                catalogPage.submit()
                catalogPage.checkPublished()
            }
            step("Следующая поставка меняет каталог; принятая корзина остаётся на 480 рублей") {
                setWebDriver(supplier)
                supplierPage.newDelivery()
                supplierPage.fill(product, 1, "300.00", "Меняющаяся цена", "Снимок оформления")
                supplierPage.send()
                supplierPage.checkPosted()
                assertEquals("360.00", ui.stock(product, 9).unitPrice)
                setWebDriver(buyer)
                catalogPage.refresh()
                catalogPage.checkTotal("480.00")
                ui.checkExpense(product, 1, 2)
            }
        }
    }

    /** Ответ теряется после принятия заявки. Перезагрузка повторяет исходные версию и ключ, сохраняя один расход товара. */
    @Test @DisplayName("Потерянный ответ оформления: перезагрузка повторяет исходный запрос")
    fun lostReply() {
        withBrowserTemplate { ui ->
            val product = preparedCart(ui)
            val path = "/stores/S-1/carts/${catalogPage.cartId()}/submit"
            step("Теряем ответ после принятия заявки") {
                ui.proxy.loseReplyAfterCommit(path)
                catalogPage.submit()
                catalogPage.checkUnknownOutcome()
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            step("Снимаем сбой и перезагружаем ту же вкладку") {
                ui.proxy.releaseFault()
                catalogPage.reload()
                catalogPage.checkPublished()
                checkReplay(ui.proxy, path, original, originalCount)
                ui.checkExpense(product, 1, 1)
            }
        }
    }

    /** После настоящего принятия браузер получает 503. Явный повтор сохраняет запрос и не создаёт второй расход. */
    @Test @DisplayName("503 после принятия: кнопка повтора сохраняет ключ и тело запроса")
    fun ambiguous503() {
        withBrowserTemplate { ui ->
            val product = preparedCart(ui)
            val path = "/stores/S-1/carts/${catalogPage.cartId()}/submit"
            step("Принимаем заявку, но показываем браузеру инфраструктурный 503") {
                ui.proxy.rejectReplyAfterCommit(path)
                catalogPage.submit()
                catalogPage.checkRetryAvailable()
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            step("Повторяем оформление кнопкой после снятия сбоя") {
                ui.proxy.releaseFault()
                catalogPage.retry()
                catalogPage.checkPublished()
                checkReplay(ui.proxy, path, original, originalCount)
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 1)
            }
        }
    }

    /** Запрос теряется до передачи в STORE. Перезагрузка повторяет те же данные и создаёт ровно одно списание. */
    @Test @DisplayName("Потерянный запрос до принятия: перезагрузка оформляет один раз с исходным ключом")
    fun lostRequest() {
        withBrowserTemplate { ui ->
            val product = preparedCart(ui)
            val path = "/stores/S-1/carts/${catalogPage.cartId()}/submit"
            step("Теряем запрос до STORE; товар ещё не списан") {
                ui.proxy.loseRequestBeforeCommit(path)
                catalogPage.submit()
                catalogPage.checkUnknownOutcome()
                assertEquals(5, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 0, 0)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            step("Перезагружаем вкладку после снятия сбоя; принимаем исходную операцию") {
                ui.proxy.releaseFault()
                catalogPage.reload()
                catalogPage.checkPublished()
                checkReplay(ui.proxy, path, original, originalCount)
                assertEquals(4, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 1)
            }
        }
    }

    /** Другой клиент меняет корзину после загрузки UI. Устаревшее оформление обновляет состав перед следующей попыткой. */
    @Test @DisplayName("Конфликт версии обновляет корзину перед следующим оформлением")
    fun versionConflict() {
        withBrowserTemplate { ui ->
            val product = preparedCart(ui)
            val cart = storeService.getCart("S-1", catalogPage.cartId()).expect(200).body<Cart>()
            step("Другой клиент меняет количество в той же корзине с текущей версией") {
                Shop.put(ui.scope, cart, Shop.requireStock(ui.scope, product), 2).expect(200)
            }
            step("Устаревшее оформление отклоняется и UI получает актуальную сумму") {
                catalogPage.submit()
                catalogPage.checkError("Корзина изменилась")
                catalogPage.checkTotal("240.00")
                assertEquals(5, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 0, 0)
            }
            step("Повторное оформление использует обновлённую корзину") {
                catalogPage.submit()
                catalogPage.checkPublished()
                assertEquals(3, Shop.requireStock(ui.scope, product).availableQuantity)
                ui.checkExpense(product, 1, 2)
            }
        }
    }

    /** Название и описание содержат текст HTML-скрипта. Браузер должен показать его буквально, без создания тегов и исполнения. */
    @Test @DisplayName("Название и описание товара отображаются текстом без исполнения HTML")
    fun safeRendering() {
        withBrowserTemplate { ui ->
            ui.browser()
            val product = ui.product()
            val name = "<img src=x onerror=\"window.compromised=true\">"
            val description = "<script>window.compromised=true</script>"
            step("Поставляем товар с недоверенным текстом") {
                supplierPage.open()
                supplierPage.fill(product, 5, "100.00", name, description)
                supplierPage.send()
                supplierPage.checkPosted()
                ui.stock(product, 5)
            }
            step("Открываем каталог и проверяем текст, DOM и отсутствие исполнения") {
                catalogPage.open()
                catalogPage.checkLiteralProduct(product, name, description)
            }
        }
    }

    /** Цена превышает точность целых чисел JavaScript. Рассчитанная сервером сумма должна отображаться точной десятичной строкой. */
    @Test @DisplayName("Большая денежная сумма отображается без потери точности")
    fun exactLargeMoney() {
        withBrowserTemplate { ui ->
            ui.browser()
            val product = ui.product()
            step("Поставляем товар с закупочной ценой выше точности JavaScript Number") {
                supplierPage.open()
                supplierPage.fill(product, 5, "9007199254740993.00", "Большая сумма", "Десятичные деньги")
                supplierPage.send()
                supplierPage.checkPosted()
                assertEquals("11709359031163290.90", ui.stock(product, 5).unitPrice)
            }
            step("Добавляем две единицы и проверяем точную серверную сумму") {
                catalogPage.open()
                catalogPage.add(product, 2, "11709359031163290.90")
                catalogPage.checkTotal("23418718062326581.80")
            }
        }
    }

    /** Ответ поставщику теряется после подтверждения Kafka. Перезагрузка сохраняет всё событие и не создаёт повторный приход. */
    @Test @DisplayName("Перезагрузка поставщика сохраняет envelope и не удваивает приход")
    fun supplierRetry() {
        withBrowserTemplate { ui ->
            ui.browser()
            val product = ui.product()
            val path = "/warehouse/technical/deliveries"
            step("Теряем ответ поставщику после реальной публикации") {
                supplierPage.open()
                supplierPage.fill(product, 7, "100.00", "Повтор поставки", "Неизменяемый envelope")
                ui.proxy.loseReplyAfterCommit(path)
                supplierPage.send()
                supplierPage.checkUnknownOutcome()
                ui.stock(product, 7)
            }
            val (original, originalCount) = originalRequests(ui.proxy, path)
            val envelope = JsonUtils.objectMapper.readValue<DeliveryReceived>(original.body)
            step("Снимаем сбой и перезагружаем форму; повторяем тот же envelope") {
                ui.proxy.releaseFault()
                supplierPage.reload()
                supplierPage.checkPosted()
                val replay = oneReplay(ui.proxy, path, originalCount)
                assertEquals(original.body, replay.body, "Full immutable supplier envelope")
                awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
                assertEquals(7, Shop.requireStock(ui.scope, product).availableQuantity)
                assertEquals("1", org.golenev.db.tables.observation.ObservationDao.scalar("store", "SELECT count(*) FROM stock_receipts WHERE store_id=? AND delivery_id=?", "S-1", envelope.payload.deliveryId))
            }
        }
    }

    /** В одном браузере переключаются S-1 и S-2. Корзина второго магазина пуста, а возврат восстанавливает состав первого. */
    @Test @DisplayName("Переключение магазина сохраняет независимую корзину каждого магазина")
    fun storesAndPages() {
        withBrowserTemplate { ui ->
            preparedCart(ui)
            val firstCart = catalogPage.cartId()
            step("Переключаемся в другой магазин с пустой корзиной") {
                catalogPage.selectStore("S-2")
                catalogPage.checkEmpty()
                assertNotEquals(firstCart, catalogPage.cartId("S-2"))
            }
            step("Возвращаемся в первый магазин и видим исходную корзину") {
                catalogPage.selectStore("S-1")
                catalogPage.checkLine("1 × 120.00")
                assertEquals(firstCart, catalogPage.cartId())
            }
        }
    }

    /** Открываются сохранённые HTML-страницы. Они используют API конкретного магазина без удалённых маршрутов авторизации и общей корзины. */
    @Test @DisplayName("Сохранённые HTML-страницы используют новые API без авторизации")
    fun preservedPages() {
        withBrowserTemplate { ui ->
            ui.browser()
            step("Открываем сохранённые страницы и рабочий каталог") {
                navigationPage.open("/index.html")
                navigationPage.open("/login.html")
                catalogPage.open()
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

    /** Наполненная корзина отображается в области шириной 390 CSS-пикселей. Горизонтальная прокрутка не должна появиться. */
    @Test @DisplayName("Мобильная корзина помещается в viewport 390 пикселей")
    fun mobileLayout() {
        withBrowserTemplate { ui ->
            preparedCart(ui, mobile = true)
            step("Проверяем ширину наполненной мобильной корзины") {
                catalogPage.checkViewportWidth(390)
                catalogPage.checkFitsViewport()
            }
        }
    }

    /** Общая подготовка: настоящая HTML-поставка пяти единиц и добавление одной в корзину. Возвращает productId; текущий WebDriver остаётся доступен Selenide; ожидаемый исход сценарий проверяет явно. */
    private fun preparedCart(ui: BrowserScenario, mobile: Boolean = false): String {
        ui.browser(mobile)
        val product = ui.product()
        step("Поставляем пять единиц через HTML и наполняем свою корзину одной единицей") {
            supplierPage.open()
            supplierPage.fill(product, 5, "100.00", "Тестовый товар", "Подготовка независимой корзины")
            supplierPage.send()
            supplierPage.checkPosted()
            ui.stock(product, 5)
            catalogPage.open()
            catalogPage.add(product, 1)
            catalogPage.checkTotal("120.00")
        }
        return product
    }

    /** Сохраняет исходные сетевые попытки до ручного повтора. Chrome может повторить оборванный POST; все попытки обязаны сохранять один ключ и тело. */
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

    /** Проверяет один явный повтор после заранее зафиксированных сетевых попыток. Их физическое количество остаётся доступным для диагностики. */
    private fun oneReplay(proxy: BrowserProxy, path: String, originalCount: Int): BrowserRequest {
        val requests = proxy.requests(path)
        assertEquals(originalCount + 1, requests.size, "Original physical attempts plus explicit replay: $path")
        return requests[originalCount]
    }

    /** Сравнивает ключ и тело повтора с исходным запросом. Независимо проверяет, что первоначальная версия корзины равна единице. */
    private fun checkReplay(proxy: BrowserProxy, path: String, original: BrowserRequest, originalCount: Int) {
        val replay = oneReplay(proxy, path, originalCount)
        assertFalse(required(original.key, "original Idempotency-Key").isBlank())
        assertEquals(SubmitCart(1), JsonUtils.objectMapper.readValue<SubmitCart>(original.body), "Original version before acceptance")
        assertEquals(original.key, replay.key, "Original operation key survives retry")
        assertEquals(original.body, replay.body, "Original request survives retry")
    }
}
