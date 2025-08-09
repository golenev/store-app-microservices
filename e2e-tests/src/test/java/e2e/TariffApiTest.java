package e2e;

import e2e.constants.Endpoints;
import org.junit.jupiter.api.Test;

import static e2e.config.RestClient.given;
import static org.hamcrest.Matchers.*;

public class TariffApiTest {

    @Test
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
    void missingParamReturnsBadRequest() {
        given()
                .baseUri(Endpoints.TARIFFS_BASE_URL)
                .when()
                .get(Endpoints.TARIFFS)
                .then()
                .statusCode(400);
    }
}

