package org.golenev.ui.allure

/**
 * Хранит соответствия между нормализованными локаторами и человекочитаемыми именами UI-элементов в рамках потока.
 */
object UiElementNameRegistry {
    private val aliasesByLocator = ThreadLocal.withInitial { linkedMapOf<String, String>() }

    /**
     * Регистрирует алиас для локатора, если локатор и алиас не пустые после нормализации.
     *
     * @param locator исходный локатор элемента или коллекции.
     * @param alias человекочитаемое имя, связанное с локатором.
     */
    fun register(locator: String, alias: String) {
        val normalizedLocator = LocatorNormalizer.normalize(locator)
        val normalizedAlias = alias.trim()
        if (normalizedLocator.isBlank() || normalizedAlias.isBlank()) return
        aliasesByLocator.get()[normalizedLocator] = normalizedAlias
    }

    /**
     * Ищет зарегистрированный алиас по локатору с учётом нормализации.
     *
     * @param locator локатор, для которого нужно найти человекочитаемое имя.
     * @return найденный алиас или `null`, если локатор не регистрировался.
     */
    fun findAlias(locator: String): String? = aliasesByLocator.get()[LocatorNormalizer.normalize(locator)]

    /**
     * Очищает все алиасы, зарегистрированные в текущем потоке выполнения.
     */
    fun clear() {
        aliasesByLocator.remove()
    }
}
