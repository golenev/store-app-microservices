package org.golenev.tests.e2e_tests

import com.codeborne.selenide.Selenide
import com.codeborne.selenide.WebDriverRunner.getSelenideProxy
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.qameta.allure.AllureId
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.golenev.commondto.Cart
import org.golenev.commondto.DeliveryReceived
import org.golenev.commondto.SubmitCart
import org.golenev.config.Environment
import org.golenev.db.tables.receivedEvents.ReceivedEventsDao
import org.golenev.db.tables.stockExpenses.StockExpensesDao
import org.golenev.db.tables.stockReceipts.StockReceiptsDao
import org.golenev.restapi.endpoints.StoreServiceDao
import org.golenev.ui.config.*
import org.golenev.ui.pages.catalogPage
import org.golenev.ui.pages.navigationPage
import org.golenev.ui.pages.supplierPage
import org.golenev.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.*

/** Сквозные проверки HTML через Kotlin и Selenide: собственные браузеры, управляемые сетевые сбои и настоящая цепочка поставки до STORE. */
@Epic("Учебный магазин")
@Feature("HTML: каталог, корзина и поставщик")
class HtmlE2ETest {
    private val requestFilters = mutableListOf<String>()
    private val responseFilters = mutableListOf<String>()
    private val storeService = StoreServiceDao()
    private val createdProductId = "P-${UUID.randomUUID()}"
    private val scope: Scope = "S-1" to "MOSCOW"

    /** Настраивает Selenide и читаемый Allure listener как в референсе. Тест создаёт собственные товары до проверок. */
    @BeforeEach
    fun setUp() {
        DriverConfig().setup()
    }

    /** Снимает зарегистрированные фильтры после теста, закрывает браузер и очищает реестр Allure. Данные поставок и корзин сохраняются. */
    @AfterEach
    fun tearDown() {
        try {
            requestFilters.forEach { getSelenideProxy().removeRequestFilter(it) }
            responseFilters.forEach { getSelenideProxy().removeResponseFilter(it) }
        } finally {
            Selenide.closeWebDriver()
            requestFilters.clear()
            responseFilters.clear()
            com.codeborne.selenide.logevents.SelenideLogger.removeListener<com.codeborne.selenide.logevents.LogEventListener>("ReadableAllureSelenide")
            org.golenev.ui.allure.UiElementNameRegistry.clear()
        }
    }
    /** Новая поставка меняет цену открытой корзины. После оформления ещё одна поставка не должна изменить принятую сумму. */
    @Test @AllureId("150") @DisplayName("Открытая корзина обновляет цену, принятая заявка сохраняет снимок")
    fun priceSnapshot() {
        val product = createdProductId
        step("Готовим поставку пяти единиц товара $product по закупочной цене 100 рублей") {
            supplierPage.open()
            supplierPage.fill(product, 5, "100.00", "Меняющаяся цена", "Снимок оформления")
        }
        step("Отправляем первоначальную поставку товара $product") { supplierPage.send() }
        step("Проверяем оприходование пяти единиц товара $product") {
            supplierPage.checkPosted()
            awaitPoll { Shop.stock(scope, product).shouldNotBeNull().availableQuantity.shouldBe(5) }
        }
        step("Добавляем две единицы товара $product в открытую корзину") {
            catalogPage.open()
            catalogPage.add(product, 2)
        }
        step("Проверяем первоначальную сумму открытой корзины 240 рублей") { catalogPage.checkTotal("240.00") }
        step("Готовим пополнение товара $product по закупочной цене 200 рублей") {
            supplierPage.open()
            supplierPage.newDelivery()
            supplierPage.fill(product, 5, "200.00", "Меняющаяся цена", "Снимок оформления")
        }
        step("Отправляем пополнение товара $product") { supplierPage.send() }
        step("Проверяем пополнение до десяти единиц и переоценку открытой корзины до 480 рублей") {
            supplierPage.checkPosted()
            awaitPoll { Shop.stock(scope, product).shouldNotBeNull().availableQuantity.shouldBe(10) }
            catalogPage.open()
            catalogPage.checkTotal("480.00")
        }
        step("Оформляем корзину с обновлённой ценой товара $product") { catalogPage.submit() }
        step("Проверяем принятие и передачу заявки на 480 рублей") { catalogPage.checkPublished() }
        step("Готовим следующую поставку товара $product по закупочной цене 300 рублей") {
            supplierPage.open()
            supplierPage.newDelivery()
            supplierPage.fill(product, 1, "300.00", "Меняющаяся цена", "Снимок оформления")
        }
        step("Отправляем следующую поставку товара $product") { supplierPage.send() }
        step("Проверяем новую цену остатка и неизменную сумму принятой заявки") {
            supplierPage.checkPosted()
            val stock = awaitPoll {
                val stock = Shop.stock(scope, product).shouldNotBeNull()
                stock.availableQuantity.shouldBe(9)
                stock
            }
            stock.unitPrice.shouldBe("360.00")
            catalogPage.open()
            catalogPage.checkTotal("480.00")
            val expenses = StockExpensesDao.findByProductId(product)
            expenses.size.shouldBe(1)
            expenses.sumOf { it.quantity }.shouldBe(2)
        }
    }

    /** После настоящего принятия Selenide подменяет JSON ответа повреждённым телом. Перезагрузка повторяет исходные ключ и версию; расход остаётся один. */
    @Test @AllureId("151") @DisplayName("Нечитаемый ответ оформления: перезагрузка повторяет исходный запрос")
    fun lostResponse() {
        val product = prepareCartTemplate()
        val path = step("Получаем текущую корзину покупателя") {
            "/stores/S-1/carts/${catalogPage.cartId()}/submit"
        }
        val proxy = getSelenideProxy()

        val keys = interceptSubmissionKeys(proxy, path) {
            val originalBody = interceptRequestBody(proxy, path) {
                replaceResponseBody(proxy, path, "{broken") {
                    step("Отправляем оформление подготовленной корзины") { catalogPage.submit() }
                    step("Проверяем неизвестный результат оформления при уже списанном товаре") {
                        catalogPage.checkUnknownOutcome()
                        (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(4)
                    }
                }
            }
            val repeatedBody = interceptRequestBody(proxy, path) {
                step("Перезагружаем вкладку с сохранённым оформлением") { catalogPage.reload() }
                step("Проверяем передачу ранее принятой заявки") { catalogPage.checkPublished() }
            }
            step("Проверяем повтор исходного оформления и единственное списание") {
                (JsonUtils.objectMapper.readValue<SubmitCart>(originalBody)).shouldBe(SubmitCart(1))
                repeatedBody.shouldBe(originalBody)
                val expenses = StockExpensesDao.findByProductId(product)
                expenses.size.shouldBe(1)
                expenses.sumOf { it.quantity }.shouldBe(1)
            }
        }
        step("Проверяем сохранение ключа оформления при повторе") {
            keys.size.shouldBe(2)
            keys.first().shouldNotBeNull()
            keys.distinct().size.shouldBe(1)
        }
    }

    /** Встроенный прокси Selenide заменяет настоящий ответ 202 на 503 после фиксации заявки. Кнопка повтора сохраняет исходный запрос и один расход. */
    @Test @AllureId("152") @DisplayName("503 после принятия: кнопка повтора сохраняет ключ и тело запроса")
    fun ambiguous503() {
        val product = prepareCartTemplate()
        val path = step("Получаем текущую корзину покупателя") {
            "/stores/S-1/carts/${catalogPage.cartId()}/submit"
        }
        val proxy = getSelenideProxy()

        val keys = interceptSubmissionKeys(proxy, path) {
            responseFilters += replaceNextResponseStatus(proxy, path, 503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")
            val originalBody = interceptRequestBody(proxy, path) {
                step("Отправляем оформление подготовленной корзины") { catalogPage.submit() }
                step("Проверяем возможность повтора и остаток после принятия заявки") {
                    catalogPage.checkRetryAvailable()
                    (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(4)
                }
            }
            val repeatedBody = interceptRequestBody(proxy, path) {
                step("Повторяем оформление кнопкой") { catalogPage.retry() }
                step("Проверяем передачу ранее принятой заявки") { catalogPage.checkPublished() }
            }
            step("Проверяем повтор исходного оформления и единственное списание") {
                repeatedBody.shouldBe(originalBody)
                val expenses = StockExpensesDao.findByProductId(product)
                expenses.size.shouldBe(1)
                expenses.sumOf { it.quantity }.shouldBe(1)
            }
        }
        step("Проверяем сохранение ключа оформления при повторе") {
            keys.size.shouldBe(2)
            keys.first().shouldNotBeNull()
            keys.distinct().size.shouldBe(1)
        }
    }

    /** Фильтр Selenide возвращает 503 до передачи POST в STORE. Фильтр отклоняет только первый запрос; перезагрузка оформляет исходную операцию один раз, а фильтр удаляется после теста. */
    @Test @AllureId("153") @DisplayName("Отказ до принятия: перезагрузка оформляет один раз с исходным ключом")
    fun lostRequest() {
        val product = prepareCartTemplate()
        val path = step("Получаем текущую корзину покупателя") {
            "/stores/S-1/carts/${catalogPage.cartId()}/submit"
        }
        val proxy = getSelenideProxy()

        // BrowserUp выполняет последний добавленный фильтр первым: наблюдатели регистрируются после отказа.
        requestFilters += rejectNextRequest(proxy, path)
        val keys = interceptSubmissionKeys(proxy, path) {
            val originalBody = interceptRequestBody(proxy, path) {
                step("Отправляем первоначальное оформление корзины") { catalogPage.submit() }
                step("Проверяем неизвестный результат и отсутствие списания товара") {
                    catalogPage.checkUnknownOutcome()
                    (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(5)
                    val expenses = StockExpensesDao.findByProductId(product)
                    expenses.size.shouldBe(0)
                    expenses.sumOf { it.quantity }.shouldBe(0)
                }
            }
            val repeatedBody = interceptRequestBody(proxy, path) {
                step("Перезагружаем вкладку и повторяем первоначальное оформление") { catalogPage.reload() }
                step("Проверяем принятие и передачу повторного оформления") { catalogPage.checkPublished() }
            }
            step("Проверяем повтор исходного оформления и единственное списание") {
                repeatedBody.shouldBe(originalBody)
                (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(4)
                val expenses = StockExpensesDao.findByProductId(product)
                expenses.size.shouldBe(1)
                expenses.sumOf { it.quantity }.shouldBe(1)
            }
        }
        step("Проверяем сохранение ключа оформления при повторе") {
            keys.size.shouldBe(2)
            keys.first().shouldNotBeNull()
            keys.distinct().size.shouldBe(1)
        }
    }

    /** Другой клиент меняет корзину после загрузки UI. Устаревшее оформление обновляет состав перед следующей попыткой. */
    @Test @AllureId("154") @DisplayName("Конфликт версии обновляет корзину перед следующим оформлением")
    fun versionConflict() {
        val product = prepareCartTemplate()
        val cart = step("Получаем текущую корзину покупателя") {
            storeService.getCart("S-1", catalogPage.cartId()).`as`(Cart::class.java)
        }
        step("Другой клиент меняет количество в той же корзине с текущей версией") {
            Shop.put(scope, cart, Shop.stock(scope, product).shouldNotBeNull(), 2)
        }
        step("Отправляем оформление с устаревшей корзиной") { catalogPage.submit() }
        step("Проверяем отказ и обновлённую сумму корзины") {
            catalogPage.checkError("Корзина изменилась")
            catalogPage.checkTotal("240.00")
            (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(5)
            val expenses = StockExpensesDao.findByProductId(product)
            expenses.size.shouldBe(0)
            expenses.sumOf { it.quantity }.shouldBe(0)
        }
        step("Оформляем обновлённую корзину") { catalogPage.submit() }
        step("Проверяем передачу заявки и списание обновлённого количества") {
            catalogPage.checkPublished()
            (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(3)
            val expenses = StockExpensesDao.findByProductId(product)
            expenses.size.shouldBe(1)
            expenses.sumOf { it.quantity }.shouldBe(2)
        }
    }

    /** Название и описание содержат текст HTML-скрипта. Браузер должен показать его буквально, без создания тегов и исполнения. */
    @Test @AllureId("155") @DisplayName("Название и описание товара отображаются текстом без исполнения HTML")
    fun safeRendering() {
        val product = createdProductId
        val name = "<img src=x onerror=\"window.compromised=true\">"
        val description = "<script>window.compromised=true</script>"
        step("Готовим поставку товара $product с недоверенными названием и описанием") {
            supplierPage.open()
            supplierPage.fill(product, 5, "100.00", name, description)
        }
        step("Отправляем поставку товара $product на приёмку") { supplierPage.send() }
        step("Проверяем оприходование пяти единиц товара $product") {
            supplierPage.checkPosted()
            awaitPoll {
                Shop.stock(scope, product).shouldNotBeNull().availableQuantity.shouldBe(5)
            }
        }
        step("Открываем каталог с оприходованным товаром $product") { catalogPage.open() }
        step("Проверяем буквальный текст товара $product и отсутствие исполнения кода") {
            catalogPage.checkLiteralProduct(product, name, description)
        }
    }

    /** Цена превышает точность целых чисел JavaScript. Рассчитанная сервером сумма должна отображаться точной десятичной строкой. */
    @Test @AllureId("156") @DisplayName("Большая денежная сумма отображается без потери точности")
    fun exactLargeMoney() {
        val product = createdProductId
        step("Готовим поставку товара $product с большой закупочной ценой") {
            supplierPage.open()
            supplierPage.fill(product, 5, "9007199254740993.00", "Большая сумма", "Десятичные деньги")
        }
        step("Отправляем поставку товара $product на приёмку") { supplierPage.send() }
        step("Проверяем пять единиц товара $product и точную рассчитанную цену") {
            supplierPage.checkPosted()
            val stock = awaitPoll {
                val stock = Shop.stock(scope, product).shouldNotBeNull()
                stock.availableQuantity.shouldBe(5)
                stock
            }
            stock.unitPrice.shouldBe("11709359031163290.90")
        }
        step("Добавляем две единицы товара $product в корзину") {
            catalogPage.open()
            catalogPage.add(product, 2, "11709359031163290.90")
        }
        step("Проверяем точную сумму корзины без потери копеек") {
            catalogPage.checkTotal("23418718062326581.80")
        }
    }

    /** После публикации поставки прокси Selenide подменяет JSON ответа. Перезагрузка сохраняет всё событие; новый eventId той же поставки служит границей обработки повтора. */
    @Test @AllureId("157") @DisplayName("Перезагрузка поставщика сохраняет событие и не удваивает приход")
    fun supplierRetry() {
        val product = createdProductId
        step("Заполняем поставку семью единицами товара") {
            supplierPage.open()
            supplierPage.fill(product, 7, "100.00", "Повтор поставки", "Неизменяемое событие")
        }
        val proxy = getSelenideProxy()
        val path = "/technical/deliveries"
        val originalBody = interceptRequestBody(proxy, path) {
            replaceResponseBody(proxy, path, "{broken") {
                step("Отправляем подготовленную поставку товара $product") { supplierPage.send() }
                step("Проверяем неизвестный результат отправки и оприходование товара $product") {
                    supplierPage.checkUnknownOutcome()
                    awaitPoll {
                        val stock = Shop.stock(scope, product).shouldNotBeNull()
                        stock.availableQuantity.shouldBe(7)
                    }
                }
            }
        }
        val repeatedBody = interceptRequestBody(proxy, path) {
            step("Перезагружаем форму с сохранённой поставкой товара $product") { supplierPage.reload() }
            step("Проверяем оприходование повторно отправленной поставки") { supplierPage.checkPosted() }
        }
        step("Проверяем сохранённый результат и отсутствие повторного движения товара") {
            repeatedBody.shouldBe(originalBody)
        }
        val envelope = JsonUtils.objectMapper.readValue<DeliveryReceived>(originalBody)
        val marker = step("Подготавливаем данные поставки") {
            envelope.copy(eventId = UUID.randomUUID().toString())
        }
        step("Отправляем поставку на приёмку") {
            Shop.publish(marker)
        }
        step("Проверяем сохранённый результат и отсутствие повторного движения товара") {
            awaitPoll {
                (ReceivedEventsDao.findByEventId(marker.eventId).size).shouldBe(1)
            }
            (Shop.stock(scope, product).shouldNotBeNull().availableQuantity).shouldBe(7)
            (StockReceiptsDao.findByDeliveryId("S-1", envelope.payload.deliveryId).size).shouldBe(1)
        }
    }

    /** В одном браузере переключаются S-1 и S-2. Корзина второго магазина пуста, а возврат восстанавливает состав первого. */
    @Test @AllureId("158") @DisplayName("Переключение магазина сохраняет независимую корзину каждого магазина")
    fun storesAndPages() {
        prepareCartTemplate()
        val firstCart = step("Получаем текущую корзину покупателя") {
            catalogPage.cartId()
        }
        step("Переключаемся в другой магазин с пустой корзиной") {
            catalogPage.selectStore("S-2")
            catalogPage.checkEmpty()
            (catalogPage.cartId("S-2")).shouldNotBe(firstCart)
        }
        step("Возвращаемся в первый магазин и видим исходную корзину") {
            catalogPage.selectStore("S-1")
            catalogPage.checkLine("1 × 120.00")
            (catalogPage.cartId()).shouldBe(firstCart)
        }
    }

    /** Сохранённые страницы обращаются к scoped API без авторизации. Запросы наблюдает встроенный прокси Selenide. */
    @Test @AllureId("159") @DisplayName("Сохранённые HTML-страницы используют новые API без авторизации")
    fun preservedPages() {
        step("Открываем главную страницу магазина") { Selenide.open("/index.html") }
        val requests = interceptPageRequests(getSelenideProxy()) {
            step("Открываем сохранённые страницы и рабочий каталог") {
                navigationPage.open("/index.html")
                navigationPage.open("/login.html")
                catalogPage.open()
            }
        }
        step("Проверяем обращения к корзине и отсутствие авторизации") {
            requests.any { (requestPath, _) -> requestPath.matches(Regex("/stores/S-1/carts/[a-f0-9-]+")) }.shouldBeTrue()
            requests.forEach { (requestPath, authorization) ->
                withClue(requestPath) {
                    authorization.shouldBe(null)
                    Regex("^/(cart|order|products|login|auth)(/|$)").containsMatchIn(requestPath).shouldBeFalse()
                }
            }
        }
    }

    /** Наполненная корзина отображается в области шириной 390 CSS-пикселей. Горизонтальная прокрутка не должна появиться. */
    @Test @AllureId("160") @DisplayName("Мобильная корзина помещается в viewport 390 пикселей")
    fun mobileLayout() {
        DriverConfig().setup(Environment.STORE_URL, mobile = true)
        prepareCartTemplate()
        step("Проверяем ширину наполненной мобильной корзины") {
            catalogPage.checkViewportWidth(390)
            catalogPage.checkFitsViewport()
        }
    }

    /** Общая подготовка: настоящая HTML-поставка пяти единиц и добавление одной в корзину. Возвращает productId; текущий WebDriver остаётся доступен Selenide; ожидаемый исход сценарий проверяет явно. */
    private fun prepareCartTemplate(): String {
        val product = createdProductId
        step("Готовим поставку пяти единиц товара $product для покупателя") {
            supplierPage.open()
            supplierPage.fill(product, 5, "100.00", "Тестовый товар", "Подготовка независимой корзины")
        }
        step("Отправляем поставку товара $product на приёмку") { supplierPage.send() }
        step("Проверяем оприходование пяти единиц товара $product") {
            supplierPage.checkPosted()
            awaitPoll { Shop.stock(scope, product).shouldNotBeNull().availableQuantity.shouldBe(5) }
        }
        step("Добавляем одну единицу товара $product в корзину покупателя") {
            catalogPage.open()
            catalogPage.add(product, 1)
        }
        step("Проверяем сумму подготовленной корзины 120 рублей") { catalogPage.checkTotal("120.00") }
        return product
    }

}
