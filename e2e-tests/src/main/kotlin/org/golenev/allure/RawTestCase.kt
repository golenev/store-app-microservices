package org.golenev.allure

/** Данные RawTestCase для передачи между операциями теста; экземпляр не содержит изменяемого состояния сценария. */
internal data class RawTestCase(
    val baseId: String?, // 455 из AS_ID, ещё без -1/-2
    val name: String,    // чистое название теста
    val scenario: String, // блок "**Сценарий**: ..." со всеми шагами
    val category: String // suite = имя категории/класса
)
