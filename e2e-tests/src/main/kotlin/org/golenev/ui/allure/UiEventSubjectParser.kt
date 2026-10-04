package org.golenev.ui.allure

/**
 * Разбирает subject из события Selenide в тип операции, условие успеха и пояснение `because`.
 */
object UiEventSubjectParser {
    private val checkOperations = listOf("should not have", "should not be", "should have", "should be", "should not", "should")
    private val actionConditions = linkedMapOf(
        "context click" to "clickable: interactable и enabled",
        "double click" to "clickable: interactable и enabled",
        "set selected" to "элемент должен существовать для изменения selected-состояния",
        "set value" to "editable: interactable, enabled и не readonly",
        "send keys" to "элемент должен существовать для отправки клавиш",
        "unfocus" to "элемент должен существовать для снятия фокуса",
        "submit" to "элемент должен существовать для отправки формы",
        "clear" to "editable: interactable, enabled и не readonly",
        "click" to "clickable: interactable и enabled",
        "hover" to "элемент должен существовать для выполнения hover",
        "type" to "editable: interactable, enabled и не readonly",
    )

    /**
     * Определяет, является ли subject проверкой или действием, и возвращает структурированное описание события.
     *
     * @param subject строковое описание операции Selenide из `LogEvent.subject`.
     * @return разобранное UI-событие или `null`, если subject пустой либо не поддерживается.
     */
    fun parse(subject: String): ParsedUiEvent? {
        val normalizedSubject = subject.trim()
        if (normalizedSubject.isBlank()) return null

        val check = parseCheck(normalizedSubject)
        if (check != null) return check

        val action = actionConditions.entries.firstOrNull { (operation) ->
            normalizedSubject == operation || normalizedSubject.startsWith("$operation(")
        } ?: return null

        return ParsedUiEvent(
            eventType = UiEventType.ACTION,
            operation = normalizedSubject,
            successCondition = action.value,
            because = null,
        )
    }

    /**
     * Разбирает subject проверки Selenide и извлекает условие успешного выполнения.
     *
     * @param subject нормализованное строковое описание проверки.
     * @return разобранное событие проверки или `null`, если subject не похож на проверку.
     */
    private fun parseCheck(subject: String): ParsedUiEvent? {
        val operation = checkOperations.firstOrNull { subject == it || subject.startsWith("$it(") } ?: return null
        val condition = subject.removePrefix(operation).trim().removeOuterParentheses()
        val becauseResult = extractBecause(condition)
        return ParsedUiEvent(
            eventType = UiEventType.CHECK,
            operation = operation,
            successCondition = becauseResult.first.ifBlank { null },
            because = becauseResult.second,
        )
    }

    /**
     * Удаляет одну внешнюю пару скобок, если она охватывает всю строку целиком.
     *
     * @return строку без внешних скобок либо исходную строку, если скобки не являются общей обёрткой.
     */
    private fun String.removeOuterParentheses(): String {
        val value = trim()
        if (!value.startsWith("(") || !value.endsWith(")")) return value
        var depth = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && index < value.lastIndex) return value
        }
        return value.substring(1, value.lastIndex).trim()
    }

    /**
     * Извлекает вложенное пояснение `(because ...)` из условия проверки и возвращает условие без этого фрагмента.
     *
     * @param condition строковое условие проверки, возможно содержащее пояснение `because`.
     * @return результат с очищенным условием и найденным пояснением.
     */
    private fun extractBecause(condition: String): Pair<String, String?> {
        val marker = "(because "
        var depth = 0
        var start = -1
        var index = 0
        while (index <= condition.length - marker.length) {
            val char = condition[index]
            if (char == '(') {
                if (condition.startsWith(marker, index)) {
                    start = index
                    break
                }
                depth++
            } else if (char == ')' && depth > 0) {
                depth--
            }
            index++
        }
        if (start < 0) return condition.trim() to null

        var end = -1
        depth = 0
        (start until condition.length).forEach { i ->
            if (end >= 0) return@forEach
            when (condition[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                    }
                }
            }
        }
        if (end < 0) return condition.trim() to null

        val because = condition.substring(start + marker.length, end).trim().ifBlank { null }
        val cleaned = (condition.substring(0, start) + condition.substring(end + 1)).trim()
        return cleaned to because
    }

}
