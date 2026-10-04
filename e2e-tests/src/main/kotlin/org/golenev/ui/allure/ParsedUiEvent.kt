package org.golenev.ui.allure

/**
 * Структурированное представление распознанного UI-события Selenide.
 *
 * @property eventType тип UI-события.
 * @property operation название операции Selenide.
 * @property successCondition условие успешного выполнения или `null`, если оно отсутствует.
 * @property because пользовательское пояснение `because`, если оно было указано.
 */
data class ParsedUiEvent(
    val eventType: UiEventType,
    val operation: String,
    val successCondition: String?,
    val because: String?,
)
