package org.golenev.ui.pages

import com.codeborne.selenide.Condition.visible
import com.codeborne.selenide.Selenide
import com.codeborne.selenide.Selenide.`$`
import org.golenev.ui.allure.name

/** Проверяет навигацию сохранённых страниц, не передавая их элементы сценарию. */
class NavigationPage {
    /** Открывает заданный публичный HTML-путь и проверяет доступность навигации. */
    @io.qameta.allure.Step("Открываем страницу и ожидаем готовность")
    fun open(path: String) {
        Selenide.open(path)
        `$`("nav[aria-label='Навигация']").name("Навигация сохранённой страницы $path").shouldBe(visible)
    }
}
