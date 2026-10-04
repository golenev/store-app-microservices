package org.golenev.ui.allure

/**
 * Тип UI-события, отображаемый в человекочитаемом Allure-вложении.
 */
enum class UiEventType {
    /**
     * Проверка состояния элемента через should/shouldBe/shouldHave и родственные операции.
     */
    CHECK,

    /**
     * Действие пользователя или браузера над элементом, например click, set value или hover.
     */
    ACTION,
}
