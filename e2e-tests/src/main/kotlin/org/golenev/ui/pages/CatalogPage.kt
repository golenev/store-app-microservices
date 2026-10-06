package org.golenev.ui.pages

import com.codeborne.selenide.CollectionCondition.size
import com.codeborne.selenide.Condition.*
import com.codeborne.selenide.Selenide
import com.codeborne.selenide.SelenideWait
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.WebDriverRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.qameta.allure.Step
import org.golenev.ui.allure.name
import java.time.Duration

/** Страница каталога и корзины хранит приватные CSS-локаторы. Сценарий вызывает действия и проверки, не получая DOM-элементы. */
class CatalogPage {
    /** Проверяет заданную ширину области отображения в CSS-пикселях, независимо от размера окна браузера. */
    @Step("Проверяем ширину области отображения {expectedWidth}")
    fun checkViewportWidth(expectedWidth: Int) {
        (Selenide.executeJavaScript<Long>("return window.innerWidth").shouldNotBeNull()).shouldBe(expectedWidth.toLong())
    }
    private val cards = Selenide.`$$`("[data-product-id]").name("Карточки товаров каталога")
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
    @Step("Открываем страницу и ожидаем готовность")
    fun open() {
        Selenide.open("/products.html")
        newCart.shouldBe(visible, enabled)
    }

    /** Выбирает магазин на уже открытой странице и ждёт готовность относящейся к нему корзины. */
    @Step("Выбираем магазин {storeId}")
    fun selectStore(storeId: String) {
        storePicker.shouldBe(visible, enabled).selectOptionByValue(storeId)
        newCart.shouldBe(enabled)
    }

    /** Задаёт количество через карточку продукта с нужным UUID и проверяет отображённую серверную позицию корзины. */
    @Step("Добавляем товар {productId} в корзину")
    fun add(productId: String, quantity: Int, expectedUnitPrice: String = "120.00") {
        val card = cards.findBy(attribute("data-product-id", productId)).name("Карточка продукта $productId")
        val input = card.find("input[name='quantity']").name("Количество продукта $productId в своей корзине")
        val add = card.find("button[type='submit']").name("Положить продукт $productId в корзину")
        card.shouldBe(visible)
        input.shouldBe(enabled).setValue(quantity.toString()).shouldHave(value(quantity.toString()))
        add.shouldBe(enabled).click()
        cartItems.shouldHave(text("$quantity × $expectedUnitPrice"))
        submit.shouldBe(enabled)
    }

    /** Обновляет каталог до появления ожидаемого остатка и цены; ожидание доставки между сервисами остаётся внутри страницы. */
    @Step("Ожидаем товар {productId}: остаток {quantity}, цена {unitPrice}")
    fun awaitProduct(productId: String, quantity: Int, unitPrice: String) {
        SelenideWait(WebDriverRunner.getWebDriver(), 40000, 500).until {
            val card = cards.findBy(attribute("data-product-id", productId))
            if (card.exists() && card.find(".stock").text == "Доступно: $quantity" && card.find(".price").text == "$unitPrice ₽") {
                true
            } else {
                refresh.shouldBe(enabled.because("обновление каталога должно быть доступно после завершения предыдущего запроса")).click()
                refresh.shouldBe(enabled.because("новый каталог должен загрузиться перед проверкой поставки"))
                false
            }
        }
        val card = cards.findBy(attribute("data-product-id", productId))
        card.shouldBe(visible.because("оприходованный товар должен появиться в каталоге"))
        card.find(".stock").shouldHave(exactText("Доступно: $quantity").because("каталог должен показывать ожидаемый остаток"))
        card.find(".price").shouldHave(exactText("$unitPrice ₽").because("цена должна включать тарифную наценку"))
    }

    /** Читает идентификатор остатка из формы выбранного товара для независимого сопоставления с сообщением заявки. */
    @Step("Читаем идентификатор позиции каталога {productId}")
    fun stockItemId(productId: String): String {
        val card = cards.findBy(attribute("data-product-id", productId))
        return card.shouldBe(visible.because("позиция должна быть доступна до чтения её идентификатора"))
            .find("form").getAttribute("data-stock").shouldNotBeNull()
    }

    /** Нажимает оформление заявки. Ожидаемый успешный переход или отказ сценарий проверяет отдельно. */
    @Step("Отправляем оформление корзины")
    fun submit() {
        submit.shouldBe(visible, enabled).click()
    }

    /** Повторяет сохранённую UI операцию после неоднозначного ответа. Тест не создаёт новый ключ. */
    @Step("Повторяем исходное оформление корзины")
    fun retry() {
        retry.shouldBe(visible, enabled).click()
    }

    /** Обновляет каталог и корзину кнопкой приложения, сохраняя текущий пользовательский процесс. */
    @Step("Обновляем каталог и корзину")
    fun refresh() {
        refresh.shouldBe(visible, enabled).click()
    }

    /** Перезагружает ту же вкладку, сохраняя её идентификаторы в sessionStorage. */
    @Step("Перезагружаем страницу с сохранённой операцией")
    fun reload() {
        Selenide.refresh()
    }

    /** Ждёт отображённое подтверждение передачи заявки в Kafka. Это не подтверждение оплаты. */
    @Step("Проверяем передачу принятой заявки")
    fun checkPublished() {
        operation.shouldHave(text("PUBLISHED — заявка передана в Kafka."), Duration.ofSeconds(40))
    }

    /** Проверяет состояние неизвестного результата оформления перед повтором или перезагрузкой. */
    @Step("Проверяем сообщение о неизвестном результате операции")
    fun checkUnknownOutcome() {
        operation.shouldHave(text("Результат оформления неизвестен"))
    }

    /** Проверяет доступность кнопки повторного оформления после неоднозначного инфраструктурного ответа. */
    @Step("Проверяем доступность повторного оформления")
    fun checkRetryAvailable() {
        retry.shouldBe(visible, enabled)
    }

    /** Проверяет ожидаемое сообщение пользователю, не делая выводов о публикации по тексту ошибки. */
    @Step("Проверяем сообщение об ошибке {expectedText}")
    fun checkError(expectedText: String) {
        message.shouldHave(text(expectedText))
    }

    /** Проверяет точную серверную сумму корзины. Вычисления денег с плавающей точкой в браузере не используются. */
    @Step("Проверяем сумму корзины {expectedAmount}")
    fun checkTotal(expectedAmount: String) {
        total.shouldHave(exactText("Итого: $expectedAmount ₽"))
    }

    /** Проверяет отображённый состав той же корзины. */
    @Step("Проверяем состав корзины {expectedText}")
    fun checkLine(expectedText: String) {
        cartItems.shouldHave(text(expectedText))
    }

    /** Проверяет пустую корзину выбранного магазина, независимо от сохранённой корзины другого магазина. */
    @Step("Проверяем пустую корзину")
    fun checkEmpty() {
        cartItems.shouldHave(exactText("Корзина пуста."))
    }

    /** Проверяет буквальное отображение недоверенных названия и описания, без исполняемых DOM-узлов. */
    @Step("Проверяем буквальное отображение товара {productId}")
    fun checkLiteralProduct(productId: String, expectedName: String, expectedDescription: String) {
        val card = cards.findBy(attribute("data-product-id", productId)).name("Небезопасный текст продукта $productId")
        card.find("h3").name("Название продукта $productId").shouldHave(exactText(expectedName))
        card.find("p:not(.price):not(.stock)").name("Описание продукта $productId").shouldHave(exactText(expectedDescription))
        card.findAll("img,script").name("Недопустимые исполняемые узлы продукта $productId").shouldHave(size(0))
        val compromised = Selenide.executeJavaScript<Any>("return window.compromised")
        withClue("Текст товара $productId не должен исполнять код") { compromised.shouldBeNull() }
    }

    /** Читает сохранённый cartId нужного магазина для сопоставления операции UI с сервером и историей запросов. */
    @Step("Читаем идентификатор текущей корзины")
    fun cartId(storeId: String = "S-1"): String {
        return Selenide.executeJavaScript<String>("const raw=sessionStorage.getItem(arguments[0]); return raw ? JSON.parse(raw).cartId : null", "shop:v1:cart:$storeId").shouldNotBeNull()
    }

    /** Проверяет, что ширина документа после отрисовки помещается в мобильную область отображения. */
    @Step("Проверяем отсутствие горизонтальной прокрутки")
    fun checkFitsViewport() {
        withClue("Horizontal overflow at ${WebDriverRunner.getWebDriver().manage().window().size}") { (Selenide.executeJavaScript<Boolean>("return document.documentElement.scrollWidth <= window.innerWidth").shouldNotBeNull()).shouldBeTrue() }
    }
}
