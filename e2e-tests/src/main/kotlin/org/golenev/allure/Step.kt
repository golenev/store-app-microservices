package org.golenev.allure
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Данные Step для передачи между операциями теста; экземпляр не содержит изменяемого состояния сценария. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Step(
    val name: String,
    val parameters: List<Parameter>?,
    val steps: List<Step>?
)
