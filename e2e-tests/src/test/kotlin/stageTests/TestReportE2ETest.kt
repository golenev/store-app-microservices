package stageTests

import allure.parseAllureReportsFromFolder
import helpers.step
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.qameta.allure.AllureId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@DisplayName("Парсинг Allure отчёта")
class TestReportE2ETest {

    @AllureId("170")
    @Test
    @DisplayName("преобразование JSON в модель тест-кейса")
    fun parseAllureJsonIntoModel() {
        val tempDir = step("Создаём временную директорию для тестового отчёта") {
            Files.createTempDirectory("allure-report-json").toFile()
        }

        try {
            val reportFile = step("Записываем минимальный JSON-файл Allure") {
                val jsonContent = """
                    {
                      "name": "Sample test",
                      "testStage": {
                        "steps": [
                          {"name": "Шаг 1", "parameters": [{"name": "id", "value": "123"}], "steps": []},
                          {"name": "Шаг 2", "steps": []}
                        ]
                      },
                      "labels": [
                        {"name": "AS_ID", "value": "777"},
                        {"name": "suite", "value": "Категория отчётов"}
                      ]
                    }
                """.trimIndent()
                File(tempDir, "sample.json").apply { writeText(jsonContent) }
            }

            val models = step("Парсим тест-кейсы из временной папки") {
                parseAllureReportsFromFolder(tempDir.absolutePath)
            }

            val model = step("Извлекаем распарсенный тест-кейс") {
                models.shouldHaveSize(1)
                models.first()
            }

            step("Проверяем корректность заполненных полей") {
                model.id shouldBe "777"
                model.name shouldBe "Sample test"
                model.category shouldBe "Категория отчётов"
                model.scenario.contains("Шаг 1") shouldBe true
                model.scenario.contains("Шаг 2") shouldBe true
            }

            step("Подтверждаем, что исходный файл создан") {
                reportFile.exists() shouldBe true
            }
        } finally {
            step("Удаляем временную директорию отчёта") {
                tempDir.deleteRecursively()
            }
        }
    }
}
