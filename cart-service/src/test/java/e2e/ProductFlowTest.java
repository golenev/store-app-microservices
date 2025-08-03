package e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import io.restassured.RestAssured;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = com.experience_kafka.KafkaSpringApplication.class
)
class ProductFlowTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    ProductPayload product;

    @AfterEach
    void cleanup() {
        if (product != null) {
            jdbcTemplate.update("DELETE FROM cart WHERE barcode_id = ?", product.barcodeId());
            jdbcTemplate.update("DELETE FROM product WHERE barcode_id = ?", product.barcodeId());
        }
    }

    @Test
    void productAppearsInListAndCart() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;

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
                .post("/api/v1/auth")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(product)
                .when()
                .post("/api/v1/sendToKafka")
                .then()
                .statusCode(200);

        await().atMost(Duration.ofSeconds(5)).until(() -> {
            List<Long> barcodes = given()
                    .header("Authorization", token)
                    .when()
                    .get("/api/v1/products")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getList("barcodeId", Long.class);
            return barcodes.contains(product.barcodeId());
        });

        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(Map.of("barcodeId", product.barcodeId()))
                .when()
                .post("/api/cart")
                .then()
                .statusCode(200);

        Integer qty = jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Integer.class,
                product.barcodeId());

        Assertions.assertNotNull(qty);
        Assertions.assertEquals(1, qty);
    }

    record ProductPayload(Long barcodeId, String shortName, String description,
                          BigDecimal price, int quantity, String addedAtWarehouse,
                          boolean isFoodstuff) {
    }
}
