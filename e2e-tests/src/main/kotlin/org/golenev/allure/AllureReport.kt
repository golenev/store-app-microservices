package org.golenev.allure
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Данные AllureReport для передачи между операциями теста; экземпляр не содержит изменяемого состояния сценария. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AllureReport(
    val name: String?,
    val testStage: TestStage?,
    val beforeStages: List<TestStage>?,
    val afterStages: List<TestStage>?,
    val labels: List<Label>? // здесь лежат AS_ID, suite и прочие лейблы
)
