package org.golenev.ui.allure

/**
 * Описывает текстовое вложение Allure для одного UI-события Selenide.
 *
 * @property alias человекочитаемое имя элемента, если оно было зарегистрировано.
 * @property locator нормализованный локатор элемента.
 * @property eventType тип UI-события: проверка или действие.
 * @property operation название выполненной операции.
 * @property successCondition условие успешного выполнения операции, если оно известно.
 * @property because пользовательское пояснение `because`, извлечённое из условия проверки.
 * @property status статус выполнения события Selenide.
 * @property errorMessage текст ошибки, если событие завершилось с ошибкой.
 */
data class UiEventAttachment(
    val alias: String?,
    val locator: String,
    val eventType: UiEventType,
    val operation: String,
    val successCondition: String?,
    val because: String?,
    val status: String,
    val errorMessage: String?,
)
