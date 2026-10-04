package org.golenev.ui.pages

import com.codeborne.selenide.Selenide
import com.codeborne.selenide.Selenide.`$`
import org.golenev.ui.allure.name
import com.codeborne.selenide.Condition.*

/** Проверяет навигацию сохранённых страниц, не передавая их элементы сценарию. */
class NavigationPage {
    /** Открывает заданный публичный HTML-путь и проверяет доступность навигации. */
    fun open(path: String) {
        Selenide.open(path)
        `$`("nav[aria-label='Навигация']").name("Навигация сохранённой страницы $path").shouldBe(visible)
    }
}
