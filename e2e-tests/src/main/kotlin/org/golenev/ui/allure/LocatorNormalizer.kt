package org.golenev.ui.allure

/**
 * Приводит локаторы Selenide и Selenium к единому текстовому формату для сопоставления в Allure-отчётах.
 */
object LocatorNormalizer {
    /**
     * Очищает локатор от служебных обёрток Selenide, префиксов коллекций и Selenium By-префиксов.
     *
     * @param locator исходное строковое представление локатора; может быть `null`.
     * @return нормализованный локатор или пустую строку, если входное значение пустое.
     */
    fun normalize(locator: String?): String {
        val trimmed = locator?.trim().orEmpty()
        if (trimmed.isBlank()) return ""

        return trimmed
            .removeSelenideWrapper()
            .removeCollectionWrapper()
            .removeByPrefix()
            .trim()
    }

    /**
     * Убирает обёртку `$('<локатор>')` или `$("<локатор>")`, которую Selenide добавляет в строковое описание элемента.
     *
     * @return содержимое обёртки Selenide либо исходную строку, если обёртка отсутствует.
     */
    private fun String.removeSelenideWrapper(): String {
        val value = trim()
        if (!value.startsWith("$(") || !value.endsWith(")")) return value

        val content = value.substring(2, value.lastIndex).trim()
        if (content.length < 2) return content

        val quote = content.first()
        return if ((quote == '\'' || quote == '"') && content.last() == quote) {
            content.substring(1, content.lastIndex)
        } else {
            content
        }
    }

    /**
     * Убирает префикс описания коллекции элементов, оставляя только локатор коллекции.
     *
     * @return локатор без префикса коллекции либо исходную строку, если префикс не найден.
     */
    private fun String.removeCollectionWrapper(): String {
        val value = trim()
        return when {
            value.startsWith("Elements collection") -> value.substringAfter("Elements collection").trim().trimStart(':').trim()
            value.startsWith("Collection") -> value.substringAfter("Collection").trim().trimStart(':').trim()
            else -> value
        }
    }

    /**
     * Убирает Selenium-префикс типа локатора из строкового представления `By`.
     *
     * @return локатор без префикса `By.cssSelector` или `By.xpath`, либо исходную строку.
     */
    private fun String.removeByPrefix(): String {
        val value = trim()
        return when {
            value.startsWith("By.cssSelector: ") -> value.removePrefix("By.cssSelector: ").trim()
            value.startsWith("By.xpath: ") -> value.removePrefix("By.xpath: ").trim()
            else -> value
        }
    }
}
