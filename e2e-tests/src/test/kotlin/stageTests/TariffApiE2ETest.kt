package stageTests

import config.HttpClient
import constants.Endpoints
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import positiveConfig

@DisplayName("Тесты API тарифов")
class TariffApiE2ETest {

    @Test
    @DisplayName("возвращает тарифы в формате JSON")
    fun returnsTariffsAsJson() {
        runBlocking {
            eventually(positiveConfig) {
                val response = HttpClient.get(
                    url = Endpoints.TARIFFS,
                    params = mapOf("all" to true),
                    baseUri = Endpoints.TARIFFS_BASE_URL
                )
                response.statusCode shouldBe 200
                val tariffs = response.jsonPath().getList<Any>("$")
                tariffs.shouldNotBeEmpty()
            }
        }
    }

    @Test
    @DisplayName("без параметра all получаем 400")
    fun missingParamReturnsBadRequest() {
        runBlocking {
            eventually(positiveConfig) {
                val response = HttpClient.get(
                    url = Endpoints.TARIFFS,
                    baseUri = Endpoints.TARIFFS_BASE_URL
                )
                response.statusCode shouldBe 400
            }
        }
    }
}

