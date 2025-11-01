package stageTests;

import io.restassured.RestAssured;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

@Testcontainers // Подключает поддержку Testcontainers для запуска контейнеров перед тестами
@SpringBootTest( // Инстанцирует рабочий Spring Boot контекст сервиса внутри интеграционного теста
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = com.experience_kafka.KafkaSpringApplication.class // Загружает корневую конфигурацию сервиса: Spring создаёт идентичный граф бинов и подключений, поэтому весь технологический ландшафт (включая единственную продукционную БД) реплицируется в одноразовом Testcontainers-инстансе вместе с обнаруженными @Entity
)
@AutoConfigureWireMock(port = 0) // Настраивает WireMock с динамическим портом для эмуляции внешнего сервиса
@TestPropertySource(properties = "tariffs-service.base-url=http://localhost:${wiremock.server.port}") // Переопределяет URL сервиса тарифов, указывая на WireMock
@DisplayName("Проверка появления товара и ограничения корзины")
class ProductFlowTest {

    @Container // Помечает контейнер PostgreSQL как управляемый жизненным циклом Testcontainers
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine"); // Создаёт контейнер PostgreSQL на базе образа postgres:16-alpine

    @Container // Помечает контейнер Kafka как управляемый Testcontainers
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0")); // Создаёт контейнер Kafka из указанного Docker-образа

    @DynamicPropertySource // Позволяет программно переопределить свойства приложения во время тестов
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); // Перенастраивает DataSource на временную БД контейнера, поэтому Hibernate подключается именно к ней
        registry.add("spring.datasource.username", postgres::getUsername); // Подставляет учётные данные контейнера, делая тестовую БД единственной доступной для Hibernate
        registry.add("spring.datasource.password", postgres::getPassword); // Завершает конфигурацию DataSource для временного контейнера PostgreSQL
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update"); // Hibernate генерирует схему и типы данных по метаданным @Entity (например CartItem с @Table("cart")), найденным при старте контекста Spring Boot
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @LocalServerPort // Впрыскивает случайный порт, на котором запущен встроенный сервер
    int port;

    @Autowired // Автоматически подставляет бин JdbcTemplate из контекста Spring
    JdbcTemplate jdbcTemplate;

    ProductPayload product;

    @BeforeEach
    void stubTariffs() {
        stubFor(get(urlPathEqualTo("/tariffs"))
                .withQueryParam("all", equalTo("true"))
                .willReturn(okJson("""
                        [{"productType":"any","markupCoefficient":1.0}]""")));
    }

    @AfterEach
    void cleanup() {
        if (product != null) {
            jdbcTemplate.update("DELETE FROM cart WHERE barcode_id = ?", product.barcodeId());
            jdbcTemplate.update("DELETE FROM product WHERE barcode_id = ?", product.barcodeId());
        }
        reset();
    }

    @Test
    @DisplayName("товар появляется и не добавляется сверх остатка")
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

        // получаем токен авторизации
        String token = given()
                .contentType("application/json")
                .body(Map.of("username", "user", "password", "qwerty"))
                .when()
                .post("/api/v1/auth")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        // отправляем товар в kafka
        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(product)
                .when()
                .post("/api/v1/sendToKafka")
                .then()
                .statusCode(200);

        await().atMost(Duration.ofSeconds(20)).until(() -> {
            // запрашиваем список продуктов до появления нашего товара
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

        // добавляем товар в корзину
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

        // повторная попытка добавить товар в корзину
        given()
                .header("Authorization", token)
                .contentType("application/json")
                .body(Map.of("barcodeId", product.barcodeId()))
                .when()
                .post("/api/cart")
                .then()
                .statusCode(400);

        // проверяем, что количество в корзине не изменилось
        Integer qtyAfter = jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Integer.class,
                product.barcodeId());
        Assertions.assertEquals(1, qtyAfter);
    }

    record ProductPayload(Long barcodeId, String shortName, String description,
                          BigDecimal price, int quantity, String addedAtTariffs,
                          boolean isFoodstuff) {
    }
}
