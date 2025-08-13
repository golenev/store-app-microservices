package stageTests

import constants.Endpoints
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Тесты API тарифов")
class TariffApiTest {

    @Test
    @DisplayName("возвращает тарифы в формате JSON")
    fun returnsTariffsAsJson() {
        val response = Given {
            baseUri(Endpoints.TARIFFS_BASE_URL)
            param("all", true)
        } When {
            get(Endpoints.TARIFFS)
        }
        response.statusCode shouldBe 200
        val tariffs = response.jsonPath().getList<Any>("$")
        tariffs.shouldNotBeEmpty()
    }

    @Test
    @DisplayName("без параметра all получаем 400")
    fun missingParamReturnsBadRequest() {
        val response = Given {
            baseUri(Endpoints.TARIFFS_BASE_URL)
        } When {
            get(Endpoints.TARIFFS)
        }
        response.statusCode shouldBe 400
    }
}

