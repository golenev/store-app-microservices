package org.golenev.allure
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Данные TestStage для передачи между операциями теста; экземпляр не содержит изменяемого состояния сценария. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TestStage(
    val name: String? = null,
    val steps: List<Step>?
)
