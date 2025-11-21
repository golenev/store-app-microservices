package allure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File


// ---- Модели для парсинга Allure JSON ----

@JsonIgnoreProperties(ignoreUnknown = true)
data class AllureReport(
    val name: String?,
    val testStage: TestStage?,
    val beforeStages: List<TestStage>?,
    val afterStages: List<TestStage>?,
    val labels: List<Label>? // здесь лежат AS_ID, suite и прочие лейблы
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TestStage(
    val name: String? = null,
    val steps: List<Step>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Step(
    val name: String,
    val parameters: List<Parameter>?,
    val steps: List<Step>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Parameter(
    val name: String,
    val value: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(
    val name: String?,
    val value: String?
)

// ---- Модель, которую возвращаем наружу ----

data class TestCaseModel(
    val id: String,      // 455 или 455-1, 455-2...
    val name: String,    // название теста
    val scenario: String, // сценарий в том же виде, как раньше печатался
    val category: String // значение @DisplayName на классе (Allure label "suite")
)

// Внутренняя DTO: результат парсинга одного файла до окончательного назначения ID
private data class RawTestCase(
    val baseId: String?, // 455 из AS_ID, ещё без -1/-2
    val name: String,    // чистое название теста
    val scenario: String, // блок "**Сценарий**: ..." со всеми шагами
    val category: String // suite = имя категории/класса
)

// Счётчик шагов
private class Counter {
    private var value = 1
    fun next(): Int = value++
}

// ----------------- Вспомогательные функции -----------------

// Парсинг одного JSON в RawTestCase
private fun extractRawTestCase(jsonString: String, fileName: String): RawTestCase {
    val mapper = jacksonObjectMapper()
    val report = try {
        mapper.readValue<AllureReport>(jsonString)
    } catch (e: Exception) {
        throw IllegalStateException("Ошибка при парсинге JSON из файла $fileName: ${e.message}", e)
    }

    fun processSteps(steps: List<Step>?, indent: Int = 0, stepCounter: Counter): List<String> {
        val seenSteps = mutableSetOf<String>()
        val lines = mutableListOf<String>()

        fun traverse(steps: List<Step>?, level: Int) {
            steps?.forEach { step ->
                val skip = listOf("beforeStages", "afterStages", "attachments").any {
                    step.name.contains(it, ignoreCase = true)
                }
                if (skip) return@forEach

                val key = "${step.name}:${step.parameters?.joinToString { it.value } ?: ""}"
                if (key in seenSteps) return@forEach
                seenSteps.add(key)

                val prefix = " ".repeat(level * 2) + "• ${stepCounter.next()}. ${step.name}"

                val withParams = buildString {
                    step.parameters?.takeIf { it.isNotEmpty() }?.let { params ->
                        appendLine()
                        appendLine("```")
                        params.forEach { param ->
                            appendLine("${param.name}=${param.value}")
                        }
                        append("```")
                    }
                }

                lines.add(prefix + withParams)
                traverse(step.steps, level + 1)
            }
        }

        traverse(steps, indent)
        return lines
    }

    fun renderBlock(title: String, steps: List<Step>?): String {
        val lines = processSteps(steps, stepCounter = Counter())
        return if (lines.isNotEmpty()) {
            buildString {
                appendLine("$title:")
                lines.forEach { appendLine(it) }
                appendLine()
            }
        } else ""
    }

    // Название теста — как раньше, только без префикса "**Название теста:**"
    val testName = report.name
        ?.replace(Regex("\\s+"), " ")
        ?: "Без названия"

    // Сценарий — тот самый блок "**Сценарий**", который раньше печатался
    val scenarioBlock = renderBlock("**Сценарий**", report.testStage?.steps)
        .ifBlank { "Шаги не найдены" }

    // ID из лейбла AS_ID
    val baseId = report.labels
        ?.firstOrNull { it.name == "AS_ID" }
        ?.value

    // Категория из лейбла suite (DisplayName над классом)
    val category = report.labels
        ?.firstOrNull { it.name == "suite" }
        ?.value
        ?: "Без категории"

    return RawTestCase(
        baseId = baseId,
        name = testName,
        scenario = scenarioBlock,
        category = category
    )
}

// ----------------- Основная функция, которую ты вызываешь -----------------

/**
 * Парсит все JSON-файлы Allure в указанной папке и возвращает список TestCaseModel.
 *
 * @param folderPath путь к папке с Allure test-cases,
 *                   например "build/reports/allure-report/allureReport/data/test-cases"
 */
fun parseAllureReportsFromFolder(folderPath: String): List<TestCaseModel> {
    val folder = File(folderPath)
    require(folder.exists() && folder.isDirectory) {
        "Папка не найдена или не является директорией: $folderPath"
    }

    val jsonFiles = folder.listFiles { file ->
        file.isFile && file.extension.equals("json", ignoreCase = true)
    } ?: emptyArray()

    require(jsonFiles.isNotEmpty()) {
        "JSON-файлы не найдены в папке: $folderPath"
    }

    // Парсим все файлы в RawTestCase
    val rawCases: MutableList<Pair<File, RawTestCase>> = mutableListOf()

    jsonFiles.forEach { file ->
        val content = file.readText()
        val raw = extractRawTestCase(content, file.name)
        rawCases.add(file to raw)
    }

    // Проверяем, что у всех есть AS_ID
    val withoutId = rawCases.filter { it.second.baseId == null }
    if (withoutId.isNotEmpty()) {
        val fileNames = withoutId.joinToString { it.first.name }
        throw IllegalStateException("Для некоторых тестов не найден AS_ID (Label name='AS_ID'): $fileNames")
    }

    // Группируем по baseId (AS_ID), чтобы понять, какие тесты параметризованные
    val groupedByBaseId: Map<String, List<Pair<File, RawTestCase>>> =
        rawCases.groupBy { it.second.baseId!! }

    val result = mutableListOf<TestCaseModel>()

    groupedByBaseId.forEach { (baseId, group) ->
        if (group.size == 1) {
            // Обычный тест: ID = "455"
            val raw = group.first().second
            result.add(
                TestCaseModel(
                    id = baseId,
                    name = raw.name,
                    scenario = raw.scenario,
                    category = raw.category
                )
            )
        } else {
            val sortedGroup = group.sortedBy { it.first.name }
            sortedGroup.forEachIndexed { index, (_, raw) ->
                val runNumber = index + 1
                val id = "$baseId-$runNumber"
                result.add(
                    TestCaseModel(
                        id = id,
                        name = raw.name,
                        scenario = raw.scenario,
                        category = raw.category
                    )
                )
            }
        }
    }
    return result
}