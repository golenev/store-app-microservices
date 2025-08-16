package com.tariffs;

import com.tariffs.entity.Tariff;
import com.tariffs.repository.TariffRepository;
import org.junit.jupiter.api.Test;
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
class TariffServiceCacheTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tariffs_db")
            .withUsername("myuser")
            .withPassword("mypassword");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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
}
