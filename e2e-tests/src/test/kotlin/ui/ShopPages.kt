package ui

import com.codeborne.selenide.SelenideDriver
import com.codeborne.selenide.SelenideElement
import com.codeborne.selenide.ElementsCollection
import com.codeborne.selenide.Condition.*
import com.codeborne.selenide.CollectionCondition.size
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import required
import java.time.Duration

/** Adds a human-readable native Selenide alias without caching a DOM node or mutating a global registry. */
fun SelenideElement.name(description: String): SelenideElement = this.`as`(description)
/** Names a lazy collection while preserving its owner/selector and multiplicity. */
fun ElementsCollection.name(description: String): ElementsCollection = this.`as`(description)

/** Catalog/cart page owns all its lazy CSS locators; scenarios receive actions/checks rather than DOM elements. */
class CatalogPage(private val driver: SelenideDriver, private val baseUrl: String) {
    /** Checks the declared CSS viewport independently of the desktop window size. */
    fun checkViewportWidth(expectedWidth: Int) {
        org.junit.jupiter.api.Assertions.assertEquals(expectedWidth.toLong(), required(driver.executeJavaScript<Long>("return window.innerWidth"), "CSS viewport width"))
    }
    private val newCart = driver.find("#new-cart").name("Начать новую корзину")
    private val refresh = driver.find("#refresh").name("Обновить каталог и корзину")
    private val submit = driver.find("#submit").name("Оформить заявку")
    private val retry = driver.find("#retry").name("Повторить исходное оформление")
    private val storePicker = driver.find("#store-id").name("Магазин покупателя")
    private val message = driver.find("#message").name("Сообщение покупателю")
    private val total = driver.find("#cart-total").name("Серверная сумма корзины")
    private val cartItems = driver.find("#cart-items").name("Состав корзины")
    private val operation = driver.find("#operation").name("Статус принятия и передачи заявки")

    /** Opens the catalog explicitly and waits for the server-created independent cart. */
    fun open() {
        driver.open("$baseUrl/products.html")
        newCart.shouldBe(visible, enabled)
    }

    /** Selects only the already-open page's store and waits for scoped cart initialization. */
    fun selectStore(storeId: String) {
        storePicker.shouldBe(visible, enabled).selectOptionByValue(storeId)
        newCart.shouldBe(enabled)
    }

    /** Adds absolute quantity through the unique UUID product card and confirms the rendered server line. */
    fun add(productId: String, quantity: Int, expectedUnitPrice: String = "120.00") {
        val card = driver.find("[data-product-id='$productId']").name("Карточка продукта $productId")
        val input = card.find("input[name='quantity']").name("Количество продукта $productId в своей корзине")
        val add = card.find("button[type='submit']").name("Положить продукт $productId в корзину")
        card.shouldBe(visible)
        input.shouldBe(enabled).setValue(quantity.toString()).shouldHave(value(quantity.toString()))
        add.shouldBe(enabled).click()
        cartItems.shouldHave(text("$quantity × $expectedUnitPrice"))
        submit.shouldBe(enabled)
    }

    /** Clicks acceptance only; scenarios separately specify the expected success or error transition. */
    fun submit() {
        submit.shouldBe(visible, enabled).click()
    }

    /** Repeats the UI's persisted operation after an ambiguous response; no new key is manufactured by the test. */
    fun retry() {
        retry.shouldBe(visible, enabled).click()
    }

    /** Refreshes through the application's explicit action rather than reloading a different workflow implicitly. */
    fun refresh() {
        refresh.shouldBe(visible, enabled).click()
    }

    /** Reloads the same tab, preserving its sessionStorage identity. */
    fun reload() {
        driver.refresh()
    }

    /** Waits for the actual browser-visible broker acknowledgement, not a payment success. */
    fun checkPublished() {
        operation.shouldHave(text("PUBLISHED — заявка передана в Kafka."), Duration.ofSeconds(40))
    }

    /** Verifies the unknown-outcome branch before retry/reload. */
    fun checkUnknownOutcome() {
        operation.shouldHave(text("Результат оформления неизвестен"))
    }

    /** Checks the explicit retry control exposed after an ambiguous infrastructure reply. */
    fun checkRetryAvailable() {
        retry.shouldBe(visible, enabled)
    }

    /** Verifies a user-visible error message without guessing the backend's publication state. */
    fun checkError(expectedText: String) {
        message.shouldHave(text(expectedText))
    }

    /** Checks a server-rendered total exactly; client floating-point arithmetic is not used. */
    fun checkTotal(expectedAmount: String) {
        total.shouldHave(exactText("Итого: $expectedAmount ₽"))
    }

    /** Checks the same cart's server-rendered composition. */
    fun checkLine(expectedText: String) {
        cartItems.shouldHave(text(expectedText))
    }

    /** Checks the explicitly selected store's empty cart, independent of another store's saved cart. */
    fun checkEmpty() {
        cartItems.shouldHave(exactText("Корзина пуста."))
    }

    /** Confirms untrusted name/description remain literal text and create no executable DOM nodes. */
    fun checkLiteralProduct(productId: String, expectedName: String, expectedDescription: String) {
        val card = driver.find("[data-product-id='$productId']").name("Небезопасный текст продукта $productId")
        card.find("h3").name("Название продукта $productId").shouldHave(exactText(expectedName))
        card.find("p:not(.price):not(.stock)").name("Описание продукта $productId").shouldHave(exactText(expectedDescription))
        card.findAll("img,script").name("Недопустимые исполняемые узлы продукта $productId").shouldHave(size(0))
        val compromised = driver.executeJavaScript<Any>("return window.compromised")
        assertNull(compromised, "Untrusted product executed script: $productId")
    }

    /** Reads only the required persisted cart key to correlate this UI operation with scoped backend/network evidence. */
    fun cartId(storeId: String = "S-1"): String {
        return required(driver.executeJavaScript<String>("const raw=sessionStorage.getItem(arguments[0]); return raw ? JSON.parse(raw).cartId : null", "shop:v1:cart:$storeId"), "browser cart store=$storeId")
    }

    /** Confirms mobile document width fits the actual viewport after rendering. */
    fun checkFitsViewport() {
        assertTrue(required(driver.executeJavaScript<Boolean>("return document.documentElement.scrollWidth <= window.innerWidth"), "viewport overflow check"),
            "Horizontal overflow at ${driver.webDriver.manage().window().size}")
    }
}

/** Supplier page owns its form and receiving-result locators, keeping user actions explicit. */
class SupplierPage(private val driver: SelenideDriver, private val baseUrl: String) {
    private val form = driver.find("#delivery-form").name("Форма новой поставки")
    private val product = form.find("[name='productId']").name("Код продукта поставки")
    private val productName = form.find("[name='shortName']").name("Название товара поставки")
    private val description = form.find("[name='description']").name("Описание товара поставки")
    private val quantity = form.find("[name='quantity']").name("Количество поставки")
    private val price = form.find("[name='purchasePrice']").name("Закупочная цена поставки")
    private val submit = form.find("button[type='submit']").name("Отправить поставку")
    private val fresh = driver.find("#new-delivery").name("Начать следующую поставку")
    private val result = driver.find("#delivery-result").name("Результат приёмки поставки")

    /** Opens supplier UI; waits for form discovery rather than relying on a fixed startup pause. */
    fun open() {
        driver.open("$baseUrl/send-to-kafka.html")
        form.shouldBe(visible)
        com.codeborne.selenide.SelenideWait(driver.webDriver, 30000, 100).until {
            submit.isEnabled || fresh.isEnabled
        }
    }

    /** Starts the explicitly requested next delivery without silently changing the identity during input. */
    fun newDelivery() {
        fresh.shouldBe(visible, enabled).click()
        submit.shouldBe(enabled)
    }

    /** Inputs valid supplier data into an already-open new delivery and verifies controlled input values. */
    fun fill(productId: String, count: Int, purchasePrice: String, shortName: String, details: String) {
        product.shouldBe(enabled).setValue(productId).shouldHave(value(productId))
        productName.setValue(shortName).shouldHave(value(shortName))
        description.setValue(details).shouldHave(value(details))
        quantity.setValue(count.toString()).shouldHave(value(count.toString()))
        price.setValue(purchasePrice).shouldHave(value(purchasePrice))
    }

    /** Sends the current immutable envelope through the real warehouse API. */
    fun send() {
        submit.shouldBe(visible, enabled).click()
    }

    /** Reloads the same tab so the application can replay its saved supplier envelope. */
    fun reload() {
        driver.refresh()
    }

    /** Confirms the unknown supplier outcome branch before reloading its saved envelope. */
    fun checkUnknownOutcome() {
        result.shouldHave(text("Результат отправки неизвестен"))
    }

    /** Confirms actual warehouse POSTED after replay; STORE consumption is observed separately. */
    fun checkPosted() {
        result.shouldHave(text("POSTED — цена рассчитана"), Duration.ofSeconds(40))
    }
}

/** Navigation checks operate on preserved pages without exposing their elements to the scenario. */
class NavigationPage(private val driver: SelenideDriver, private val baseUrl: String) {
    /** Opens a declared public HTML path and verifies its navigation is available. */
    fun open(path: String) {
        driver.open(baseUrl + path)
        driver.find("nav[aria-label='Навигация']").name("Навигация сохранённой страницы $path").shouldBe(visible)
    }
}
