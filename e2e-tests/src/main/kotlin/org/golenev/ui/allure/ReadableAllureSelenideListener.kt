package org.golenev.ui.allure

import com.codeborne.selenide.logevents.LogEvent
import com.codeborne.selenide.logevents.LogEventListener
import io.qameta.allure.Allure
import io.qameta.allure.selenide.AllureSelenide

/**
 * Слушатель Selenide, который добавляет в Allure человекочитаемые вложения с деталями UI-событий.
 */
class ReadableAllureSelenideListener : LogEventListener {
    private val delegate = AllureSelenide()
        .includeSelenideSteps(false)
        .screenshots(true)
        .savePageSource(true)

    /**
     * Передаёт событие начала операции стандартному AllureSelenide-слушателю.
     *
     * @param event событие Selenide, которое началось.
     */
    override fun beforeEvent(event: LogEvent) {
        delegate.beforeEvent(event)
    }

    /**
     * Передаёт завершённое событие стандартному слушателю и добавляет текстовое вложение с описанием UI-действия.
     *
     * @param event событие Selenide после выполнения операции.
     */
    override fun afterEvent(event: LogEvent) {
        delegate.afterEvent(event)
        buildAttachment(event)?.let { attachment ->
            Allure.addAttachment(attachment.title(), "text/plain", attachment.body())
        }
    }

    /**
     * Собирает модель вложения Allure из события Selenide, если событие относится к поддерживаемой UI-операции.
     *
     * @param event событие Selenide, из которого берутся локатор, subject, статус и ошибка.
     * @return данные для текстового вложения или `null`, если локатор пустой либо subject не распознан.
     */
    internal fun buildAttachment(event: LogEvent): UiEventAttachment? {
        val locator = LocatorNormalizer.normalize(event.element)
        if (locator.isBlank()) return null

        val parsed = UiEventSubjectParser.parse(event.subject) ?: return null
        val error: Throwable? = event.error
        return UiEventAttachment(
            alias = UiElementNameRegistry.findAlias(locator),
            locator = locator,
            eventType = parsed.eventType,
            operation = parsed.operation,
            successCondition = parsed.successCondition,
            because = parsed.because,
            status = event.status.name,
            errorMessage = error?.message,
        )
    }

    /**
     * Формирует заголовок Allure-вложения из операции и человекочитаемого имени элемента.
     *
     * @return строку заголовка вложения.
     */
    private fun UiEventAttachment.title(): String = "UI · $operation · ${alias ?: locator}"

    /**
     * Формирует текстовое тело Allure-вложения с алиасом, локатором, типом операции и результатом выполнения.
     *
     * @return многострочное описание UI-события для Allure.
     */
    private fun UiEventAttachment.body(): String = buildString {
        appendLine("Алиас элемента:")
        appendLine(alias.orEmpty())
        appendLine()
        appendLine("Конечный локатор:")
        appendLine(locator)
        appendLine()
        appendLine("Тип операции:")
        appendLine(eventType.name)
        appendLine()
        appendLine("Операция:")
        appendLine(operation)
        appendLine()
        appendLine("Условие успешного выполнения:")
        appendLine(successCondition.orEmpty())
        appendLine()
        appendLine("Ожидаемая причина:")
        appendLine(because.orEmpty())
        appendLine()
        appendLine("Результат:")
        appendLine(status)
        if (!errorMessage.isNullOrBlank()) {
            appendLine()
            appendLine("Ошибка:")
            appendLine(errorMessage)
        }
    }
}
