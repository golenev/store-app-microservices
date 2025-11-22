package helpers

import allure.BatchRequest
import allure.TestCaseItem
import allure.parseAllureReportsFromFolder
import io.restassured.RestAssured
import io.restassured.http.ContentType

object MyRunner {

    @JvmStatic
    fun main(args: Array<String>) {
        println(">>> Start MyRunner")
        try {
            val testCasesPath = System.getProperty("allure.testCasesPath")
                ?: "build/reports/allure-report/allureReport/data/test-cases"

            println(">>> Start MyRunner")
            val items = parseAllureReportsFromFolder(testCasesPath)
            println(">>> Parsed test cases: ${items.size}")

            RestAssured.given()
                .contentType(ContentType.JSON)
                .body(
                    BatchRequest(
                        items = items.map { testCase ->
                            TestCaseItem(
                                testId = testCase.id,
                                category = testCase.category,
                                shortTitle = testCase.name,
                                issueLink = "",
                                readyDate = "",
                                generalStatus = "",
                                scenario = testCase.scenario,
                                notes = ""
                            )
                        }
                    )
                )
                .log().all()
                .post("http://localhost:18080/api/tests/batch")
                .then()
                .log().all()
                .statusCode(200)
            println(">>> Finished successfully")
        } catch (e: Exception) {
            e.printStackTrace()
            // Явно задаём ненулевой код возврата, чтобы Gradle всё равно считал задачу упавшей
            kotlin.system.exitProcess(1)
        }
    }
}
