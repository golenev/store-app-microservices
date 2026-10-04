package org.golenev.allure

/** Кейc из Allure с окончательным идентификатором запуска, названием, текстовым сценарием и категорией отчёта. */
data class TestCaseModel(val id: String, val name: String, val scenario: String, val category: String)
