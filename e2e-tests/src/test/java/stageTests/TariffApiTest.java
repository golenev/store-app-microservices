package stageTests;

import constants.Endpoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static config.RestClient.given;
import static org.hamcrest.Matchers.*;

@DisplayName("Тесты API тарифов")
public class TariffApiTest {

    @Test
    @DisplayName("возвращает тарифы в формате JSON")
    void returnsTariffsAsJson() {
        given()
                .baseUri(Endpoints.TARIFFS_BASE_URL)
                .param("all", true)
                .when()
                .get(Endpoints.TARIFFS)
                .then()
                .statusCode(200)
                .body("$", is(not(empty())));
    }

    @Test
    @DisplayName("без параметра all получаем 400")
    void missingParamReturnsBadRequest() {
        given()
                .baseUri(Endpoints.TARIFFS_BASE_URL)
                .when()
                .get(Endpoints.TARIFFS)
                .then()
                .statusCode(400);
    }
}

