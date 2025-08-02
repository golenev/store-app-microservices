package e2e;

import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import java.util.Map;

import static io.restassured.RestAssured.given;

public class ApiTests {

    @Test
    void authenticateAndFetchProducts() {
        String token =
                given()
                        .contentType("application/json")
                        .body(Map.of("username", "user", "password", "qwerty"))
                        .when()
                        .post("http://localhost:6789/api/v1/auth")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("token");

        given()
                .header("Authorization", token)
                .when()
                .get("http://localhost:6789/api/v1/products")
                .then()
                .statusCode(200)
                .body("$", not(empty()));
    }

}
