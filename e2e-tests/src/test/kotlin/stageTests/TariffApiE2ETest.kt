package stageTests

import config.HttpClient
import constants.Endpoints
import helpers.step
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

@DisplayName("Тесты API тарифов")
class TariffApiE2ETest {
    private val logger = LoggerFactory.getLogger(TariffApiE2ETest::class.java)

    @Test
    @DisplayName("возвращает тарифы в формате JSON")
    fun returnsTariffsAsJson() {
        step("Выполняем GET ${Endpoints.TARIFFS}?all=true и ожидаем непустой список тарифов") {
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

    @Test
    @DisplayName("без параметра all получаем 400")
    fun missingParamReturnsBadRequest() {
        step("Выполняем GET ${Endpoints.TARIFFS} без параметра all и ожидаем код 400") {
            val response = HttpClient.get(
                url = Endpoints.TARIFFS,
                baseUri = Endpoints.TARIFFS_BASE_URL
            )
            response.statusCode shouldBe 400
            logger.info("Запрос без параметра 'all' вернул 400, как и ожидалось")
        }
    }
}

