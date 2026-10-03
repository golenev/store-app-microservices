package com.tariffs;

import com.tariffs.entity.Tariff;
import com.tariffs.repository.TariffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.flywaydb.core.Flyway;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class TariffServiceCacheTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tariffs_db")
            .withUsername("myuser")
            .withPassword("mypassword");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Replaces database and Redis connections with isolated real containers; Flyway creates the schema. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TariffRepository repository;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired CacheManager cacheManager;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;

    /** Starts each cache scenario cold so execution order cannot change the legacy latency assertions. */
    @BeforeEach
    void clearCache() {
        cacheManager.getCache("tariffs").clear();
    }

    /** A clean database has seven versioned fixtures; repeated migration preserves data and HTTP health is UP. */
    @Test
    void migrationsAndHealthAreReady() {
        assertThat(jdbc.queryForObject("select count(*) from tariffs where product_type in "
                + "('food_100','food_300','food_500','food_1000','not_food_100','not_food_500','not_food_1000')",
                Integer.class)).isEqualTo(7);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(2);
        var response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    /** With a cold Redis cache, verifies the existing database delay occurs once and the next read uses cache. */
    @Test
    void firstCallHitsDbSecondUsesCache() {
        repository.save(new Tariff("test", BigDecimal.ONE));

        long start = System.currentTimeMillis();
        var first = restTemplate.getForEntity("http://localhost:" + port + "/tariffs?all=true", Tariff[].class);
        long firstDuration = System.currentTimeMillis() - start;
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();

        start = System.currentTimeMillis();
        var second = restTemplate.getForEntity("http://localhost:" + port + "/tariffs?all=true", Tariff[].class);
        long secondDuration = System.currentTimeMillis() - start;
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(firstDuration).isGreaterThanOrEqualTo(5000);
        assertThat(secondDuration).isLessThan(firstDuration);
    }

    /** After cache warmup and an HTTP reset, verifies the next call repopulates cache and later reads are faster. */
    @Test
    void cacheResetForcesNextCallToHitDb() {
        repository.save(new Tariff("reset", BigDecimal.TEN));

        // warm up cache
        restTemplate.getForEntity("http://localhost:" + port + "/tariffs?all=true", Tariff[].class);
        restTemplate.getForEntity("http://localhost:" + port + "/tariffs?all=true", Tariff[].class);

        // reset cache via API
        var resetResp = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/resetCache?now=true",
                null,
                String.class);
        assertThat(resetResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resetResp.getBody()).isEqualTo("выполнен сброс кэша");

        long start = System.currentTimeMillis();
        var afterResetFirst = restTemplate.getForEntity("http://localhost:" + port + "/tariffs?all=true", Tariff[].class);
        long firstDuration = System.currentTimeMillis() - start;
        assertThat(afterResetFirst.getStatusCode().is2xxSuccessful()).isTrue();

        start = System.currentTimeMillis();
        var afterResetSecond = restTemplate.getForEntity("http://localhost:" + port + "/tariffs?all=true", Tariff[].class);
        long secondDuration = System.currentTimeMillis() - start;
        assertThat(afterResetSecond.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(firstDuration).isGreaterThanOrEqualTo(5000);
        assertThat(secondDuration).isLessThan(firstDuration);
    }
}
