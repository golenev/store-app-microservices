package e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.awaitility.Awaitility;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

public class ProductFlowTest {

    private static final String BASE_URL = "http://localhost:6789";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ProductPayload {
        private Long barcodeId;
        private String shortName;
        private String description;
        private BigDecimal price;
        private int quantity;
        private String addedAtWarehouse;
        private boolean isFoodstuff;
    }

    @Test
    void productAppearsInListAndCart() {
        ProductPayload product = new ProductPayload(
                999L,
                "Test product",
                "Desc",
                new BigDecimal("10.00"),
                1,
                LocalDateTime.now().toString(),
                false
        );

        String token = given()
                .contentType("application/json")
                .body(Map.of("username", "user", "password", "qwerty"))
                .when()
                .post(BASE_URL + "/api/v1/auth")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(product)
                .when()
                .post(BASE_URL + "/api/v1/sendToKafka")
                .then()
                .statusCode(200);

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            List<Long> barcodes = given()
                    .header("Authorization", token)
                    .when()
                    .get(BASE_URL + "/api/v1/products")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getList("barcodeId", Long.class);
            return barcodes.contains(product.getBarcodeId());
        });

        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(Map.of("barcodeId", product.getBarcodeId()))
                .when()
                .post(BASE_URL + "/api/cart")
                .then()
                .statusCode(200);

        JdbcTemplate template = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:postgresql://localhost:34567/mydatabase",
                        "myuser",
                        "mypassword"));

        Integer qty = template.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Integer.class,
                product.getBarcodeId());

        Assertions.assertNotNull(qty);
        Assertions.assertEquals(1, qty);
    }
}

