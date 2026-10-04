package org.golenev.allure

/** Описание экспортируемого кейса: идентификатор, категория, название, ссылка, дата, статус, сценарий и заметки. */
data class TestCaseItem(val testId: String, val category: String, val shortTitle: String,
    val issueLink: String, val readyDate: String, val generalStatus: String, val scenario: String, val notes: String)
