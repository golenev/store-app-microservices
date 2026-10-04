package org.golenev.ui.allure

import com.codeborne.selenide.ElementsCollection
import com.codeborne.selenide.SelenideElement

/**
 * Регистрирует человекочитаемое имя для Selenide-элемента и возвращает этот же элемент для fluent-цепочек.
 *
 * @param alias человекочитаемое имя элемента, которое будет показано в Allure-вложениях.
 * @return текущий Selenide-элемент.
 */
fun SelenideElement.name(alias: String): SelenideElement {
    UiElementNameRegistry.register(getSearchCriteria(), alias)
    return this
}

/**
 * Регистрирует человекочитаемое имя для коллекции Selenide-элементов и возвращает эту же коллекцию для fluent-цепочек.
 *
 * @param alias человекочитаемое имя коллекции, которое будет показано в Allure-вложениях.
 * @return текущая коллекция Selenide-элементов.
 */
fun ElementsCollection.name(alias: String): ElementsCollection {
    val collectionDescription = toString()
    UiElementNameRegistry.register(collectionDescription, alias)
    UiElementNameRegistry.register(collectionDescription.removeSurrounding("[", "]"), alias)
    return this
}
