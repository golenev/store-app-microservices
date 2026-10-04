package org.golenev.ui.pages

/** Страница каталога использует текущий WebDriver потока; новый объект повторно регистрирует имена локаторов после очистки реестра. */
val catalogPage: CatalogPage get() = CatalogPage()
/** Форма поставщика использует текущий браузер и сохранённое в нём состояние поставки. */
val supplierPage: SupplierPage get() = SupplierPage()
/** Навигация сохранённых HTML-страниц текущего браузера. */
val navigationPage: NavigationPage get() = NavigationPage()
