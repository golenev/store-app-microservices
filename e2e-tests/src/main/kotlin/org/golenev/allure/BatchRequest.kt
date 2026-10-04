package org.golenev.allure

import com.fasterxml.jackson.annotation.JsonProperty

/** Пакет подготовленных описаний тест-кейсов для существующего формата экспорта. */
data class BatchRequest(@JsonProperty("items") val items: List<TestCaseItem>)
