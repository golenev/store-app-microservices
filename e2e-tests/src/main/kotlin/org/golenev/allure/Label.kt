package org.golenev.allure
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Данные Label для передачи между операциями теста; экземпляр не содержит изменяемого состояния сценария. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(
    val name: String?,
    val value: String?
)
