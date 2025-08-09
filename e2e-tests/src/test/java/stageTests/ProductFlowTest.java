package stageTests;

import config.Database;
import constants.Endpoints;
import models.ProductPayload;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static config.RestClient.given;

public class ProductFlowTest {

    private ProductPayload product;

    @AfterEach
    void cleanup() {
        if (product != null) {
            JdbcTemplate template = Database.template();
            template.update("DELETE FROM cart WHERE barcode_id = ?", product.getBarcodeId());
            template.update("DELETE FROM product WHERE barcode_id = ?", product.getBarcodeId());
        }
    }

    @Test
    void productAppearsInListAndCart() {
        product = new ProductPayload(
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
                .post(Endpoints.AUTH)
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(product)
                .when()
                .post(Endpoints.SEND_TO_KAFKA)
                .then()
                .statusCode(200);

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
            List<Long> barcodes = given()
                    .header("Authorization", token)
                    .when()
                    .get(Endpoints.PRODUCTS)
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
                .post(Endpoints.CART)
                .then()
                .statusCode(200);

        JdbcTemplate template = Database.template();
        Integer qty = template.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Integer.class,
                product.getBarcodeId());

        Assertions.assertNotNull(qty);
        Assertions.assertEquals(1, qty);
    }
}
