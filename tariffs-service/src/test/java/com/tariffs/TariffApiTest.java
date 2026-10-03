package com.tariffs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tariffs.api.TariffModels.*;
import com.tariffs.repository.TariffRuleRepository;
import com.tariffs.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual HTTP/PostgreSQL/Redis tests; each scenario clears only its own rules and the quote namespace. */
@Testcontainers
@ActiveProfiles("test")
@Import(TariffApiTest.FixedTime.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.data-source-properties.socketTimeout=1"})
class TariffApiTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired StringRedisTemplate redis;
    @Autowired TariffRuleService rules;
    @Autowired TariffQuoteService quotes;
    @Autowired TariffQuoteCache cache;
    @SpyBean TariffRuleRepository repository;
    private static final Instant FIXED_NOW = Instant.parse("2026-10-03T21:00:00Z");

    @TestConfiguration
    static class FixedTime {
        /** Fixes API timestamps without modifying the real scheduler configuration. */
        @Bean @Primary
        Clock testClock() { return Clock.fixed(FIXED_NOW, ZoneOffset.UTC); }
    }

    /** Replaces all dependency endpoints with isolated containers; production migrations remain enabled. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    /** Removes scenario rules/legacy rows and resets quotes; fixed migration fixtures are never deleted. */
    @BeforeEach
    void isolateScenario() {
        jdbc.update("DELETE FROM tariff_rules WHERE city_id LIKE 'TEST%'");
        jdbc.update("DELETE FROM tariffs WHERE product_type LIKE 'test_%'");
        quotes.reset();
        clearInvocations(repository);
    }

    /** Fresh V3/V4 create fourteen rules; repeated migrations preserve fixtures and aggregate health is UP. */
    @Test
    void migrationsPreserveFixturesAndHealth() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_rules WHERE city_id IN ('MOSCOW','SPB')", Integer.class)).isEqualTo(14);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(4);
        assertThat(http.getForEntity("/actuator/health", JsonNode.class).getBody().path("status").asText()).isEqualTo("UP");
    }

    /** Every fixture boundary selects the inclusive lower band and excludes the preceding upper band. */
    @ParameterizedTest
    @CsvSource({"NON_FOOD,0.01,0.20", "NON_FOOD,499.99,0.20", "NON_FOOD,500.00,0.25", "NON_FOOD,999.99,0.25",
            "NON_FOOD,1000.00,0.30", "NON_FOOD,99999999999999999999999999.99,0.30",
            "FOOD,0.01,0.01", "FOOD,99.99,0.01", "FOOD,100.00,0.03", "FOOD,299.99,0.03",
            "FOOD,300.00,0.05", "FOOD,499.99,0.05", "FOOD,500.00,0.10"})
    void fixtureBoundaries(String productType, String price, String rate) {
        var response = getQuote(productType, price, "MOSCOW");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("markupRate").asText()).isEqualTo(rate);
        assertThat(response.getBody().path("tariffVersion").asLong()).isEqualTo(1);
        assertThat(response.getBody().size()).isEqualTo(3);
    }

    /** The same price/product in SPB uses its own city rule, never the previously filled Moscow cache field. */
    @Test
    void cityAndProductTypeAreSeparateCacheDimensions() {
        assertThat(getQuote("NON_FOOD", "100.00", "MOSCOW").getBody().path("markupRate").asText()).isEqualTo("0.20");
        assertThat(getQuote("NON_FOOD", "100.00", "SPB").getBody().path("markupRate").asText()).isEqualTo("0.21");
        assertThat(getQuote("FOOD", "100.00", "MOSCOW").getBody().path("markupRate").asText()).isEqualTo("0.03");
        verify(repository, times(3)).matching(anyString(), anyString(), anyString(), any(BigDecimal.class));
    }

    /** An unchanged valid quote reads PostgreSQL once; exact decimal DTOs share one cache field with no TTL. */
    @Test
    void repeatedQuoteUsesCacheWithoutDatabaseReadOrTtl() {
        QuoteRequest first = new QuoteRequest("NON_FOOD", "100.00", "RUB", "MOSCOW");
        assertThat(quotes.quote(first)).isEqualTo(quotes.quote(new QuoteRequest("NON_FOOD", "100.00", "RUB", "MOSCOW")));
        verify(repository, times(1)).matching(anyString(), anyString(), anyString(), any(BigDecimal.class));
        assertThat(redis.opsForHash().size(TariffQuoteCache.ENTRIES_KEY)).isEqualTo(1);
        assertThat(redis.getExpire(TariffQuoteCache.ENTRIES_KEY)).isEqualTo(-1);
    }

    /** POST assigns UUID/version 1, PUT increments version and DELETE returns 204 with later NOT_FOUND. */
    @Test
    void crudUsesVersionedFullReplacementAndExplicitNullUpperBound() {
        var created = request(HttpMethod.POST, "/tariffs/rules", body("TEST_CRUD", "0.00", null, "0.123456"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode rule = created.getBody();
        UUID.fromString(rule.path("tariffRuleId").asText());
        assertThat(rule.path("version").asInt()).isEqualTo(1);
        assertThat(rule.has("upperBound")).isTrue();
        assertThat(rule.get("upperBound").isNull()).isTrue();
        assertThat(rule.path("markupRate").asText()).isEqualTo("0.123456");
        String path = created.getHeaders().getLocation().toString();
        assertThat(http.getForEntity(path, JsonNode.class).getBody()).isEqualTo(rule);
        assertThat(request(HttpMethod.PUT, path, body("TEST_CRUD", "5.00", "100.00", "0.0")).getBody().path("version").asInt()).isEqualTo(2);
        assertThat(http.getForEntity("/tariffs/rules", JsonNode.class).getBody().path("items").size()).isEqualTo(15);
        assertThat(request(HttpMethod.DELETE, path, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertError(http.getForEntity(path, JsonNode.class), 404, "NOT_FOUND");
        assertError(request(HttpMethod.DELETE, path, null), 404, "NOT_FOUND");
    }

    /** A cached quote retains the old rate/version after PUT; manual reset exposes the updated DB rule. */
    @Test
    void updateKeepsCachedSnapshotUntilManualReset() {
        Rule rule = rules.create(rule("TEST_UPDATE", "0.00", null, "0.20"));
        JsonNode original = getQuote("NON_FOOD", "100.00", "TEST_UPDATE").getBody();
        rules.update(rule.tariffRuleId(), rule("TEST_UPDATE", "0.00", null, "0.30"));
        assertThat(getQuote("NON_FOOD", "100.00", "TEST_UPDATE").getBody()).isEqualTo(original);
        assertThat(getQuote("NON_FOOD", "101.00", "TEST_UPDATE").getBody().path("tariffVersion").asInt()).isEqualTo(2);
        var reset = request(HttpMethod.POST, "/tariffs/cache/reset", null);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().path("resetAt").asText()).isEqualTo(FIXED_NOW.toString());
        assertThat(reset.getBody().path("cache").asText()).isEqualTo("tariff-quotes");
        assertThat(getQuote("NON_FOOD", "100.00", "TEST_UPDATE").getBody().path("markupRate").asText()).isEqualTo("0.30");
    }

    /** DELETE leaves the filled snapshot intact; after reset the same query is TARIFF_NOT_FOUND. */
    @Test
    void deletionKeepsSnapshotUntilReset() {
        Rule rule = rules.create(rule("TEST_DELETE", "0.00", null, "0.20"));
        JsonNode original = getQuote("NON_FOOD", "100.00", "TEST_DELETE").getBody();
        rules.delete(rule.tariffRuleId());
        assertThat(getQuote("NON_FOOD", "100.00", "TEST_DELETE").getBody()).isEqualTo(original);
        quotes.reset();
        assertError(getQuote("NON_FOOD", "100.00", "TEST_DELETE"), 404, "TARIFF_NOT_FOUND");
    }

    /** A miss is not cached negatively: creating a matching rule immediately makes the same query valid. */
    @Test
    void missingRuleIsNotCachedAsZeroRate() {
        assertError(getQuote("NON_FOOD", "100.00", "TEST_MISSING"), 404, "TARIFF_NOT_FOUND");
        rules.create(rule("TEST_MISSING", "0.00", null, "0.0"));
        assertThat(getQuote("NON_FOOD", "100.00", "TEST_MISSING").getBody().path("markupRate").asText()).isEqualTo("0.00");
    }

    /** Two matching intervals yield an explicit ambiguity; removing one fixes the next uncached request. */
    @Test
    void overlappingRulesNeverSelectAnArbitraryWinner() {
        rules.create(rule("TEST_OVERLAP", "0.00", "200.00", "0.20"));
        Rule overlap = rules.create(rule("TEST_OVERLAP", "100.00", null, "0.30"));
        assertError(getQuote("NON_FOOD", "150.00", "TEST_OVERLAP"), 409, "TARIFF_AMBIGUOUS");
        rules.delete(overlap.tariffRuleId());
        assertThat(getQuote("NON_FOOD", "150.00", "TEST_OVERLAP").getBody().path("markupRate").asText()).isEqualTo("0.20");
    }

    /** Reset changes only quote keys: unrelated Redis values and the retained legacy namespace survive. */
    @Test
    void resetDoesNotFlushOtherRedisNamespaces() {
        redis.opsForValue().set("other-app:sentinel", "alive");
        redis.opsForValue().set("tariffs::legacy", "legacy");
        getQuote("NON_FOOD", "100.00", "MOSCOW");
        quotes.reset();
        assertThat(redis.opsForValue().get("other-app:sentinel")).isEqualTo("alive");
        assertThat(redis.opsForValue().get("tariffs::legacy")).isEqualTo("legacy");
        assertThat(redis.hasKey(TariffQuoteCache.ENTRIES_KEY)).isFalse();
        redis.delete(List.of("other-app:sentinel", "tariffs::legacy"));
    }

    /** A controlled pre-reset cache miss cannot refill the new generation with its old DB snapshot. */
    @Test
    void inFlightOldCalculationCannotRefillAfterReset() {
        Rule rule = rules.create(rule("TEST_RACE", "0.00", null, "0.20"));
        QuoteRequest request = new QuoteRequest("NON_FOOD", "100.00", "RUB", "TEST_RACE");
        var lookup = cache.read(request);
        QuoteResponse old = rules.calculate(request);
        rules.update(rule.tariffRuleId(), rule("TEST_RACE", "0.00", null, "0.30"));
        quotes.reset();
        cache.write(request, lookup, old);
        assertThat(cache.read(request).quote()).isNull();
        assertThat(quotes.quote(request).markupRate()).isEqualTo("0.30");
    }

    /** Redis data loss starts a fresh UUID generation; a pre-restart lookup must not repopulate it. */
    @Test
    void lostGenerationDoesNotRevalidateOldFillToken() {
        QuoteRequest request = new QuoteRequest("NON_FOOD", "100.00", "RUB", "MOSCOW");
        var lookup = cache.read(request);
        QuoteResponse old = rules.calculate(request);
        redis.delete(List.of(TariffQuoteCache.EPOCH_KEY, TariffQuoteCache.ENTRIES_KEY));
        var restarted = cache.read(request);
        assertThat(restarted.generation()).isNotEqualTo(lookup.generation());
        cache.write(request, lookup, old);
        assertThat(cache.read(request).quote()).isNull();
    }

    /** The actual scheduler annotation resolves to Moscow midnight; invoking its reset clears an old snapshot. */
    @Test
    void scheduledResetUsesMoscowMidnightAndTheSameNamespace() throws Exception {
        Scheduled annotation = TariffQuoteService.class.getMethod("scheduledReset").getAnnotation(Scheduled.class);
        assertThat(annotation.zone()).isEqualTo("Europe/Moscow");
        var next = CronExpression.parse(annotation.cron()).next(ZonedDateTime.ofInstant(Instant.parse("2026-10-03T20:59:59Z"), ZoneId.of(annotation.zone())));
        assertThat(next.toInstant()).isEqualTo(FIXED_NOW);
        getQuote("NON_FOOD", "100.00", "MOSCOW");
        quotes.scheduledReset();
        assertThat(redis.hasKey(TariffQuoteCache.ENTRIES_KEY)).isFalse();
    }

    /** With a real paused Redis, quote falls back to DB and manual reset truthfully reports dependency failure. */
    @Test
    void redisOutageFallsBackToDatabaseAndResetReturns503() {
        Rule rule = rules.create(rule("TEST_OUTAGE", "0.00", null, "0.20"));
        getQuote("NON_FOOD", "100.00", "TEST_OUTAGE");
        rules.update(rule.tariffRuleId(), rule("TEST_OUTAGE", "0.00", null, "0.30"));
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            var response = getQuote("NON_FOOD", "100.00", "TEST_OUTAGE");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("markupRate").asText()).isEqualTo("0.30");
            assertThat(http.getForEntity("/actuator/health/readiness", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertError(request(HttpMethod.POST, "/tariffs/cache/reset", null), 503, "DEPENDENCY_UNAVAILABLE");
            quotes.scheduledReset();
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    /** Without a filled quote and with PostgreSQL paused, errors remain HTTP 503 instead of zero markup. */
    @Test
    void databaseOutageOnCacheMissReturns503() {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            assertError(getQuote("NON_FOOD", "123.45", "TEST_DB_DOWN"), 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
    }

    /** A filled quote remains usable during a PostgreSQL outage; no database read is attempted. */
    @Test
    void cachedQuoteSurvivesDatabaseOutage() {
        JsonNode original = getQuote("NON_FOOD", "100.00", "MOSCOW").getBody();
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            assertThat(getQuote("NON_FOOD", "100.00", "MOSCOW").getBody()).isEqualTo(original);
        } finally {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
        verify(repository, times(1)).matching(anyString(), anyString(), anyString(), any(BigDecimal.class));
    }

    /** Two concurrent replacements both succeed with distinct versions, proving the row lock prevents lost increments. */
    @Test
    void concurrentUpdatesIncrementVersionTwice() throws Exception {
        Rule rule = rules.create(rule("TEST_CONCURRENT", "0.00", null, "0.20"));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> rules.update(rule.tariffRuleId(), rule("TEST_CONCURRENT", "0.00", null, "0.30")));
            var b = workers.submit(() -> rules.update(rule.tariffRuleId(), rule("TEST_CONCURRENT", "0.00", null, "0.40")));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS).version(), b.get(10, TimeUnit.SECONDS).version())).containsExactlyInAnyOrder(2L, 3L);
            assertThat(rules.get(rule.tariffRuleId()).version()).isEqualTo(3);
        }
    }

    /** All invalid price classes are rejected before the database/cache selector can run. */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00", "1", "1.0", "1.001", "01.00", "1e2", "NaN", "100000000000000000000000000.00"})
    void invalidQuotePrices(String price) {
        assertError(getQuote("NON_FOOD", price, "MOSCOW"), 400, "VALIDATION_ERROR");
        verify(repository, never()).matching(anyString(), anyString(), anyString(), any(BigDecimal.class));
    }

    /** Unsupported currency, enum, city syntax and missing dimensions yield deterministic HTTP 400 responses. */
    @ParameterizedTest
    @ValueSource(strings = {"/tariffs/quote?productType=OTHER&purchasePrice=1.00&currency=RUB&cityId=MOSCOW",
            "/tariffs/quote?productType=FOOD&purchasePrice=1.00&currency=USD&cityId=MOSCOW",
            "/tariffs/quote?productType=FOOD&purchasePrice=1.00&currency=RUB&cityId=bad%20city",
            "/tariffs/quote?productType=FOOD&purchasePrice=1.00&currency=RUB",
            "/tariffs/rules/not-a-uuid", "/tariffs/rules/1-1-1-1-1"})
    void invalidDimensionsAndIdentifiers(String path) {
        assertError(http.getForEntity(path, JsonNode.class), 400, "VALIDATION_ERROR");
    }

    /** Equal/reversed bounds, negative rates and excessive fractional precision cannot be persisted through HTTP. */
    @ParameterizedTest
    @CsvSource({"100.00,100.00,0.20", "100.00,99.99,0.20", "0.00,100.00,-0.20", "0.00,100.00,0.1234567", "0.00,100.00,1000.0"})
    void invalidRuleValues(String lower, String upper, String rate) {
        assertError(request(HttpMethod.POST, "/tariffs/rules", body("TEST_INVALID", lower, upper, rate)), 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_rules WHERE city_id='TEST_INVALID'", Integer.class)).isZero();
    }

    /** JSON strings are mandatory: unknown/missing/duplicate fields and numeric money are rejected. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"productType\":\"NON_FOOD\",\"cityId\":\"TEST_JSON\",\"currency\":\"RUB\",\"lowerBound\":0.25,\"upperBound\":null,\"markupRate\":\"0.20\"}",
            "{\"productType\":\"NON_FOOD\",\"cityId\":\"TEST_JSON\",\"currency\":\"RUB\",\"lowerBound\":\"0.00\",\"markupRate\":\"0.20\"}",
            "{\"productType\":\"NON_FOOD\",\"cityId\":\"TEST_JSON\",\"currency\":\"RUB\",\"lowerBound\":\"0.00\",\"upperBound\":null,\"markupRate\":\"0.20\",\"extra\":true}",
            "{\"productType\":\"NON_FOOD\",\"cityId\":\"TEST_JSON\",\"currency\":\"RUB\",\"lowerBound\":\"0.00\",\"upperBound\":null,\"markupRate\":\"0.20\",\"markupRate\":\"0.30\"}", "{}", "{"})
    void malformedRuleJson(String json) {
        assertError(request(HttpMethod.POST, "/tariffs/rules", json), 400, "VALIDATION_ERROR");
    }

    /** Missing rules return NOT_FOUND on PUT, and invalid replacement does not mutate version or values. */
    @Test
    void invalidReplacementDoesNotChangeTheRule() {
        Rule existing = rules.create(rule("TEST_REPLACE", "0.00", null, "0.20"));
        assertError(request(HttpMethod.PUT, "/tariffs/rules/" + existing.tariffRuleId(), body("TEST_REPLACE", "100.00", "99.00", "0.30")), 400, "VALIDATION_ERROR");
        assertThat(rules.get(existing.tariffRuleId())).isEqualTo(existing);
        assertError(request(HttpMethod.PUT, "/tariffs/rules/" + UUID.randomUUID(), body("TEST_REPLACE", "0.00", null, "0.20")), 404, "NOT_FOUND");
    }

    /** A valid six-decimal rate remains exact in PostgreSQL, HTTP and the cached snapshot. */
    @Test
    void fractionalRateIsExactWithoutDoubleOrPrematureRounding() {
        Rule rule = rules.create(rule("TEST_PRECISION", "0.00", null, "999.123456"));
        assertThat(rules.get(rule.tariffRuleId()).markupRate()).isEqualTo("999.123456");
        assertThat(getQuote("NON_FOOD", "123.45", "TEST_PRECISION").getBody().path("markupRate").asText()).isEqualTo("999.123456");
        assertThat(getQuote("NON_FOOD", "123.45", "TEST_PRECISION").getBody().path("markupRate").asText()).isEqualTo("999.123456");
    }

    /** PostgreSQL independently enforces bound ordering even when a caller bypasses HTTP/service validation. */
    @Test
    void databaseRejectsInvalidBounds() {
        Rule rule = rules.create(rule("TEST_SQL", "0.00", null, "0.20"));
        assertThatThrownBy(() -> jdbc.update("UPDATE tariff_rules SET upper_bound=lower_bound WHERE tariff_rule_id=?", rule.tariffRuleId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(rules.get(rule.tariffRuleId())).isEqualTo(rule);
    }

    /** Rule creation rejects overflow rather than returning an invalid/truncated 1001-item v1 catalog. */
    @Test
    void creationRespectsTheCatalogLimit() {
        jdbc.update("""
                INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,markup_rate)
                SELECT md5('limit' || n)::uuid,1,'NON_FOOD','TEST_LIMIT','RUB',0,0.20 FROM generate_series(1,986) n
                """);
        assertError(request(HttpMethod.POST, "/tariffs/rules", body("TEST_OVER_LIMIT", "0.00", null, "0.20")), 400, "VALIDATION_ERROR");
        assertThat(http.getForEntity("/tariffs/rules", JsonNode.class).getBody().path("items").size()).isEqualTo(1000);
    }

    /** Two creates compete for the last catalog slot: the DB lock permits exactly one insertion. */
    @Test
    void concurrentCreatesCannotExceedCatalogLimit() throws Exception {
        jdbc.update("""
                INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,markup_rate)
                SELECT md5('race-limit' || n)::uuid,1,'NON_FOOD','TEST_LIMIT','RUB',0,0.20 FROM generate_series(1,985) n
                """);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> request(HttpMethod.POST, "/tariffs/rules", body("TEST_LAST_A", "0.00", null, "0.20")).getStatusCode().value());
            var b = workers.submit(() -> request(HttpMethod.POST, "/tariffs/rules", body("TEST_LAST_B", "0.00", null, "0.30")).getStatusCode().value());
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_rules", Integer.class)).isEqualTo(1000);
    }

    /** A malformed persisted quote is treated as a miss and replaced by an authoritative database result. */
    @Test
    void malformedCacheEntryIsRecomputed() {
        getQuote("NON_FOOD", "100.00", "MOSCOW");
        redis.opsForHash().put(TariffQuoteCache.ENTRIES_KEY, "NON_FOOD|MOSCOW|RUB|100.00", "{");
        clearInvocations(repository);
        assertThat(getQuote("NON_FOOD", "100.00", "MOSCOW").getBody().path("markupRate").asText()).isEqualTo("0.20");
        verify(repository).matching(anyString(), anyString(), anyString(), any(BigDecimal.class));
    }

    /** Unknown paths and unsupported methods keep client-error statuses instead of becoming INTERNAL_ERROR. */
    @Test
    void routingErrorsDoNotBecomeServerErrors() {
        assertError(http.getForEntity("/tariffs/unknown/path", JsonNode.class), 404, "NOT_FOUND");
        assertError(request(HttpMethod.POST, "/tariffs/quote", null), 405, "VALIDATION_ERROR");
        assertError(request(HttpMethod.PUT, "/tariffs/cache/reset", null), 405, "VALIDATION_ERROR");
    }

    /** Old STORE still reads seven percentage records; its manual reset alias keeps the original response text. */
    @Test
    void legacyStoreEndpointsRemainOperationalWithoutDelayOrLegacyCache() {
        var list = http.getForEntity("/tariffs?all=true", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().isArray()).isTrue();
        assertThat(list.getBody().size()).isEqualTo(7);
        var reset = http.postForEntity("/api/v1/resetCache?now=true", null, String.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody()).isEqualTo("выполнен сброс кэша");
    }

    /** Calls the real GET quote API using named dimensions, allowing malformed test input to reach validation. */
    private ResponseEntity<JsonNode> getQuote(String type, String price, String city) {
        return http.getForEntity("/tariffs/quote?productType={type}&purchasePrice={price}&currency=RUB&cityId={city}", JsonNode.class, type, price, city);
    }

    /** Creates an exact fractional-rule DTO; null upper bounds are intentionally preserved. */
    private RuleRequest rule(String city, String lower, String upper, String rate) {
        return new RuleRequest("NON_FOOD", city, "RUB", lower, upper, rate);
    }

    /** Serializes a normal rule request with the configured mapper, including explicit nullable upperBound. */
    private String body(String city, String lower, String upper, String rate) {
        try { return mapper.writeValueAsString(rule(city, lower, upper, rate)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    /** Sends an HTTP mutation with JSON content type and returns its unmodified status/body for assertions. */
    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    /** Verifies safe v1 error shape, UTC timestamp and status/code consistency without exposing dependency internals. */
    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode error = response.getBody();
        assertThat(error.path("status").asInt()).isEqualTo(status);
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("timestamp").asText()).isEqualTo(FIXED_NOW.toString());
        assertThat(error.path("path").asText()).startsWith("/");
        assertThat(error.path("message").asText()).isNotBlank().doesNotContain("jdbc:", "password", "SELECT ", "Exception");
    }
}
