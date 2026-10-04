package com.shop.warehouse;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Запускает WAREHOUSE с настоящей PostgreSQL и проверяет готовность сервиса и fixtures магазинов. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WarehouseRuntimeTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired TestRestTemplate http;

    /** Подключает сервис к свежему контейнеру PostgreSQL; его адрес и учётные данные принадлежат этому тестовому классу. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.admin.auto-create", () -> "false");
    }

    /** Проверяет создание двух магазинов и отсутствие повторной вставки или сброса при следующей миграции. */
    @Test
    void fixturesSurviveRepeatedMigration() {
        assertThat(jdbc.queryForList("select store_id || ':' || city from stores order by store_id", String.class))
                .containsExactly("S-1:MOSCOW", "S-2:SPB");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stores", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(3);
    }

    /** Проверяет ответ health запущенного сервиса и готовность БД; обработка поставки проверяется другими сценариями. */
    @Test
    void healthIsUp() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }


}
