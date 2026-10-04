package org.golenev.ui.pages

import com.codeborne.selenide.Selenide
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.WebDriverRunner
import org.golenev.ui.allure.name
import com.codeborne.selenide.Condition.*
import com.codeborne.selenide.CollectionCondition.size
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.golenev.utils.required
import java.time.Duration

/** Страница каталога и корзины хранит приватные CSS-локаторы. Сценарий вызывает действия и проверки, не получая DOM-элементы. */
class CatalogPage {
    /** Проверяет заданную ширину области отображения в CSS-пикселях, независимо от размера окна браузера. */
    fun checkViewportWidth(expectedWidth: Int) {
        org.junit.jupiter.api.Assertions.assertEquals(expectedWidth.toLong(), required(Selenide.executeJavaScript<Long>("return window.innerWidth"), "CSS viewport width"))
    }
    private val newCart = `$`("#new-cart").name("Начать новую корзину")
    private val refresh = `$`("#refresh").name("Обновить каталог и корзину")
    private val submit = `$`("#submit").name("Оформить заявку")
    private val retry = `$`("#retry").name("Повторить исходное оформление")
    private val storePicker = `$`("#store-id").name("Магазин покупателя")
    private val message = `$`("#message").name("Сообщение покупателю")
    private val total = `$`("#cart-total").name("Серверная сумма корзины")
    private val cartItems = `$`("#cart-items").name("Состав корзины")
    private val operation = `$`("#operation").name("Статус принятия и передачи заявки")

    /** Открывает каталог и ждёт готовность независимой корзины, созданной сервером. */
    fun open() {
        Selenide.open("/products.html")
        newCart.shouldBe(visible, enabled)
    }

    /** Выбирает магазин на уже открытой странице и ждёт готовность относящейся к нему корзины. */
    fun selectStore(storeId: String) {
        storePicker.shouldBe(visible, enabled).selectOptionByValue(storeId)
        newCart.shouldBe(enabled)
    }

    /** Задаёт количество через карточку продукта с нужным UUID и проверяет отображённую серверную позицию корзины. */
    fun add(productId: String, quantity: Int, expectedUnitPrice: String = "120.00") {
        val card = `$`("[data-product-id='$productId']").name("Карточка продукта $productId")
        val input = card.find("input[name='quantity']").name("Количество продукта $productId в своей корзине")
        val add = card.find("button[type='submit']").name("Положить продукт $productId в корзину")
        card.shouldBe(visible)
        input.shouldBe(enabled).setValue(quantity.toString()).shouldHave(value(quantity.toString()))
        add.shouldBe(enabled).click()
        cartItems.shouldHave(text("$quantity × $expectedUnitPrice"))
        submit.shouldBe(enabled)
    }

    /** Нажимает оформление заявки. Ожидаемый успешный переход или отказ сценарий проверяет отдельно. */
    fun submit() {
        submit.shouldBe(visible, enabled).click()
    }

    /** Повторяет сохранённую UI операцию после неоднозначного ответа. Тест не создаёт новый ключ. */
    fun retry() {
        retry.shouldBe(visible, enabled).click()
    }

    /** Обновляет каталог и корзину кнопкой приложения, сохраняя текущий пользовательский процесс. */
    fun refresh() {
        refresh.shouldBe(visible, enabled).click()
    }

    /** Перезагружает ту же вкладку, сохраняя её идентификаторы в sessionStorage. */
    fun reload() {
        Selenide.refresh()
    }

    /** Ждёт отображённое подтверждение передачи заявки в Kafka. Это не подтверждение оплаты. */
    fun checkPublished() {
        operation.shouldHave(text("PUBLISHED — заявка передана в Kafka."), Duration.ofSeconds(40))
    }

    /** Проверяет состояние неизвестного результата оформления перед повтором или перезагрузкой. */
    fun checkUnknownOutcome() {
        operation.shouldHave(text("Результат оформления неизвестен"))
    }

    /** Проверяет доступность кнопки повторного оформления после неоднозначного инфраструктурного ответа. */
    fun checkRetryAvailable() {
        retry.shouldBe(visible, enabled)
    }

    /** Проверяет ожидаемое сообщение пользователю, не делая выводов о публикации по тексту ошибки. */
    fun checkError(expectedText: String) {
        message.shouldHave(text(expectedText))
    }

    /** Проверяет точную серверную сумму корзины. Вычисления денег с плавающей точкой в браузере не используются. */
    fun checkTotal(expectedAmount: String) {
        total.shouldHave(exactText("Итого: $expectedAmount ₽"))
    }

    /** Проверяет отображённый состав той же корзины. */
    fun checkLine(expectedText: String) {
        cartItems.shouldHave(text(expectedText))
    }

    /** Проверяет пустую корзину выбранного магазина, независимо от сохранённой корзины другого магазина. */
    fun checkEmpty() {
        cartItems.shouldHave(exactText("Корзина пуста."))
    }

    /** Проверяет буквальное отображение недоверенных названия и описания, без исполняемых DOM-узлов. */
    fun checkLiteralProduct(productId: String, expectedName: String, expectedDescription: String) {
        val card = `$`("[data-product-id='$productId']").name("Небезопасный текст продукта $productId")
        card.find("h3").name("Название продукта $productId").shouldHave(exactText(expectedName))
        card.find("p:not(.price):not(.stock)").name("Описание продукта $productId").shouldHave(exactText(expectedDescription))
        card.findAll("img,script").name("Недопустимые исполняемые узлы продукта $productId").shouldHave(size(0))
        val compromised = Selenide.executeJavaScript<Any>("return window.compromised")
        assertNull(compromised, "Untrusted product executed script: $productId")
    }

    /** Читает сохранённый cartId нужного магазина для сопоставления операции UI с сервером и историей запросов. */
    fun cartId(storeId: String = "S-1"): String {
        return required(Selenide.executeJavaScript<String>("const raw=sessionStorage.getItem(arguments[0]); return raw ? JSON.parse(raw).cartId : null", "shop:v1:cart:$storeId"), "browser cart store=$storeId")
    }

    /** Проверяет, что ширина документа после отрисовки помещается в мобильную область отображения. */
    fun checkFitsViewport() {
        assertTrue(required(Selenide.executeJavaScript<Boolean>("return document.documentElement.scrollWidth <= window.innerWidth"), "viewport overflow check"),
            "Horizontal overflow at ${WebDriverRunner.getWebDriver().manage().window().size}")
    }
}
