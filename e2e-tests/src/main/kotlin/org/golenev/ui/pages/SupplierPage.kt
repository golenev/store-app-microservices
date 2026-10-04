package org.golenev.ui.pages

import com.codeborne.selenide.Selenide
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.WebDriverRunner
import org.golenev.ui.allure.name
import com.codeborne.selenide.Condition.*
import java.time.Duration

/** Страница поставщика хранит локаторы формы и результата приёмки. Действия пользователя выполняются явно. */
class SupplierPage {
    private val form = `$`("#delivery-form").name("Форма новой поставки")
    private val product = form.find("[name='productId']").name("Код продукта поставки")
    private val productName = form.find("[name='shortName']").name("Название товара поставки")
    private val description = form.find("[name='description']").name("Описание товара поставки")
    private val quantity = form.find("[name='quantity']").name("Количество поставки")
    private val price = form.find("[name='purchasePrice']").name("Закупочная цена поставки")
    private val submit = form.find("button[type='submit']").name("Отправить поставку")
    private val fresh = `$`("#new-delivery").name("Начать следующую поставку")
    private val result = `$`("#delivery-result").name("Результат приёмки поставки")

    /** Открывает форму поставщика и ждёт её готовность, без фиксированной паузы запуска. */
    fun open() {
        Selenide.open("/send-to-kafka.html")
        form.shouldBe(visible)
        com.codeborne.selenide.SelenideWait(WebDriverRunner.getWebDriver(), 30000, 100).until {
            submit.isEnabled || fresh.isEnabled
        }
    }

    /** Явно начинает следующую поставку, не меняя её идентификатор незаметно во время заполнения формы. */
    fun newDelivery() {
        fresh.shouldBe(visible, enabled).click()
        submit.shouldBe(enabled)
    }

    /** Заполняет готовую форму корректными данными поставки и проверяет введённые значения. */
    fun fill(productId: String, count: Int, purchasePrice: String, shortName: String, details: String) {
        product.shouldBe(enabled).setValue(productId).shouldHave(value(productId))
        productName.setValue(shortName).shouldHave(value(shortName))
        description.setValue(details).shouldHave(value(details))
        quantity.setValue(count.toString()).shouldHave(value(count.toString()))
        price.setValue(purchasePrice).shouldHave(value(purchasePrice))
    }

    /** Отправляет текущее неизменяемое событие через настоящий API WAREHOUSE. */
    fun send() {
        submit.shouldBe(visible, enabled).click()
    }

    /** Перезагружает ту же вкладку, чтобы приложение повторило сохранённое событие поставки. */
    fun reload() {
        Selenide.refresh()
    }

    /** Проверяет неизвестный результат отправки перед перезагрузкой сохранённой операции поставщика. */
    fun checkUnknownOutcome() {
        result.shouldHave(text("Результат отправки неизвестен"))
    }

    /** Ждёт настоящее состояние POSTED в WAREHOUSE после повтора. Приход в STORE проверяется отдельно. */
    fun checkPosted() {
        result.shouldHave(text("POSTED — цена рассчитана"), Duration.ofSeconds(40))
    }
}
