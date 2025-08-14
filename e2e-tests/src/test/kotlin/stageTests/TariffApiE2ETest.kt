package stageTests

import config.HttpClient
import constants.Endpoints
import helpers.step
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import positiveConfig

@DisplayName("Тесты API тарифов")
class TariffApiE2ETest {
    private val logger = LoggerFactory.getLogger(TariffApiE2ETest::class.java)

    @Test
    @DisplayName("возвращает тарифы в формате JSON")
    fun returnsTariffsAsJson() {
        runBlocking {
            eventually(positiveConfig) {
                step("Запрос тарифов с параметром all=true") {
                    val response = HttpClient.get(
                        url = Endpoints.TARIFFS,
                        params = mapOf("all" to true),
                        baseUri = Endpoints.TARIFFS_BASE_URL
                    )
                    response.statusCode shouldBe 200
                    val tariffs = response.jsonPath().getList<Any>("$")
                    tariffs.shouldNotBeEmpty()
                    logger.info("Получено {} тарифов", tariffs.size)
                }
            }
        }
    }

    @Test
    @DisplayName("без параметра all получаем 400")
    fun missingParamReturnsBadRequest() {
        runBlocking {
            eventually(positiveConfig) {
                step("Запрос тарифов без параметра all") {
                    val response = HttpClient.get(
                        url = Endpoints.TARIFFS,
                        baseUri = Endpoints.TARIFFS_BASE_URL
                    )
                    response.statusCode shouldBe 400
                    logger.info("Запрос без параметра 'all' вернул 400, как и ожидалось")
                }
            }
        }
    }
}

