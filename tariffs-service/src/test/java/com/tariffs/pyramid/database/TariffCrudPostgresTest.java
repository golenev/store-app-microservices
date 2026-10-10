package com.tariffs.pyramid.database;

import com.tariffs.dto.RuleRequest;
import com.tariffs.exception.TariffApiException;
import com.tariffs.model.Rule;

import com.tariffs.repository.TariffRuleRepository;
import com.tariffs.service.TariffRuleService;
import jakarta.validation.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Те же девять CRUD-сценариев на отдельной PostgreSQL: реальные миграции, репозиторий и транзакционный сервис. */
@Tag("database") @Testcontainers @SpringJUnitConfig(TariffCrudPostgresTest.Database.class)
class TariffCrudPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private TariffRuleService service;
    @Autowired private JdbcTemplate jdbc;
    private final Set<UUID> created = new HashSet<>();

    /** Поднимает только компоненты CRUD: Redis, web server и другие микросервисы не нужны. */
    @org.springframework.context.annotation.Configuration @EnableTransactionManagement
    static class Database {
        /** Создаёт datasource отдельного контейнера и применяет production-миграции без очистки пользовательских баз. */
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            return source;
        }
        /** Связывает JDBC с тестовой PostgreSQL; SQL выполняется на её движке. */
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        /** Предоставляет настоящие транзакции для аннотаций production-сервиса. */
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        /** Создаёт production-репозиторий, выполняющий SQL вместо ответов Mockito. */
        @Bean TariffRuleRepository repository(JdbcTemplate jdbc) { return new TariffRuleRepository(jdbc); }
        /** Создаёт Bean Validation и закрывает её фабрику при завершении контекста. */
        @Bean(destroyMethod="close") ValidatorFactory validation() { return Validation.buildDefaultValidatorFactory(); }
        /** Собирает production-сервис; Spring оборачивает его транзакционным прокси. */
        @Bean TariffRuleService service(TariffRuleRepository repository, ValidatorFactory validation) {
            return new TariffRuleService(repository, validation.getValidator());
        }
    }

    /** Удаляет только UUID, созданные текущим тестом; миграционные fixtures и чужие записи не затрагиваются. */
    @AfterEach void cleanup() { created.forEach(id -> jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?", id)); }

    /**
     * TAR-CRUD-001-DB. Создаём NON_FOOD/RUB, границы 0.00–500.00, ставку 0.20.
     * Проверяем все поля/version=1 и повторное чтение. Production SQL должен сохранить запись в PostgreSQL.
     * Вызов сервиса завершает собственную транзакцию до чтения; тест не скрывает запись общим rollback.
     */
    @Test @DisplayName("TAR-CRUD-001-DB: создание правила версии 1")
    void createsRule() {
        Rule result = create();
        assertRule(result, 1, input());
        assertThat(service.get(result.tariffRuleId())).isEqualTo(result);
    }

    /**
     * TAR-CRUD-002-DB. Создаём правило версии 1; читаем по возвращённому UUID.
     * Все поля совпадают с результатом создания: проверяется настоящий SELECT на подготовленной схеме.
     */
    @Test @DisplayName("TAR-CRUD-002-DB: чтение созданного правила")
    void readsRule() {
        Rule existing = create();
        assertThat(service.get(existing.tariffRuleId())).isEqualTo(existing);
    }

    /**
     * TAR-CRUD-003-DB. Правило версии 1 заменяем: FOOD, границы 5.00–без верхнего предела, ставка 0.30.
     * UUID сохраняется, version=2 и все новые поля доступны после commit при повторном чтении.
     * Проверка исполняет блокировку и UPDATE PostgreSQL; Redis в этом наборе отсутствует.
     */
    @Test @DisplayName("TAR-CRUD-003-DB: полная замена и версия 2")
    void replacesRule() {
        Rule previous = create();
        Rule result = service.update(previous.tariffRuleId(), replacement());
        assertThat(result.tariffRuleId()).isEqualTo(previous.tariffRuleId());
        assertRule(result, 2, replacement());
        assertThat(service.get(previous.tariffRuleId())).isEqualTo(result);
    }

    /**
     * TAR-CRUD-004-DB. Создаём и удаляем правило. После commit чтение того же UUID возвращает NOT_FOUND/404.
     * Это проверяет физическое удаление строк через production-репозиторий, а не ответ подменённой зависимости.
     */
    @Test @DisplayName("TAR-CRUD-004-DB: удаление и отсутствие при чтении")
    void deletesRule() {
        UUID id = create().tariffRuleId();
        service.delete(id);
        assertError(() -> service.get(id), 404, "NOT_FOUND");
    }

    /** TAR-CRUD-005-DB. Для нового отсутствующего UUID чтение возвращает NOT_FOUND/404 без создания записи. */
    @Test @DisplayName("TAR-CRUD-005-DB: чтение неизвестного UUID")
    void rejectsMissingRead() { assertError(() -> service.get(UUID.randomUUID()), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-006-DB. Для отсутствующего UUID отправляем допустимую замену.
     * UPDATE-сценарий возвращает NOT_FOUND/404; последующее чтение подтверждает, что запись не создана.
     */
    @Test @DisplayName("TAR-CRUD-006-DB: замена неизвестного UUID")
    void rejectsMissingUpdate() {
        UUID id = UUID.randomUUID();
        assertError(() -> service.update(id, replacement()), 404, "NOT_FOUND");
        assertError(() -> service.get(id), 404, "NOT_FOUND");
    }

    /** TAR-CRUD-007-DB. Удаление неизвестного UUID возвращает NOT_FOUND/404 вместо успешного пустого удаления. */
    @Test @DisplayName("TAR-CRUD-007-DB: удаление неизвестного UUID")
    void rejectsMissingDelete() { assertError(() -> service.delete(UUID.randomUUID()), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-008-DB. Создаём правило с границами 100.00 и 100.00.
     * Получаем VALIDATION_ERROR/400; количество правил собственного города не меняется.
     */
    @Test @DisplayName("TAR-CRUD-008-DB: неверное создание ничего не сохраняет")
    void rejectsInvalidCreate() {
        int before = countOwnRules();
        assertError(() -> service.create(invalid()), 400, "VALIDATION_ERROR");
        assertThat(countOwnRules()).isEqualTo(before);
    }

    /**
     * TAR-CRUD-009-DB. Создаём правило версии 1, затем отправляем замену с одинаковыми границами.
     * Проверяем VALIDATION_ERROR/400 и сохранение всех исходных полей/version после завершения ошибочного вызова.
     */
    @Test @DisplayName("TAR-CRUD-009-DB: неверная замена сохраняет исходное правило")
    void rejectsInvalidUpdate() {
        Rule existing = create();
        assertError(() -> service.update(existing.tariffRuleId(), invalid()), 400, "VALIDATION_ERROR");
        assertThat(service.get(existing.tariffRuleId())).isEqualTo(existing);
    }

    /** Создаёт исходное правило через production-сервис и запоминает UUID для адресной очистки после проверки. */
    private Rule create() { Rule result = service.create(input()); created.add(result.tariffRuleId()); return result; }
    /** Возвращает те же исходные бизнес-поля, что и Mockito/API-наборы. */
    private RuleRequest input() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "0.00", "500.00", "0.20"); }
    /** Возвращает ту же полную замену, что и остальные уровни; null означает отсутствие верхнего предела. */
    private RuleRequest replacement() { return new RuleRequest("FOOD", "PYRAMID", "RUB", "5.00", null, "0.30"); }
    /** Возвращает одинаковые границы для проверки отказа до сохранения. */
    private RuleRequest invalid() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "100.00", "100.00", "0.20"); }
    /** Считает только учебный город; фиксированные правила MOSCOW/SPB не служат результатом этой проверки. */
    private int countOwnRules() { return jdbc.queryForObject("SELECT count(*) FROM tariff_rules WHERE city_id='PYRAMID'", Integer.class); }
    /** Проверяет каждое поле ответа, UUID и заданную версию; ошибка частичной замены должна быть обнаружена. */
    private void assertRule(Rule result, long version, RuleRequest expected) {
        assertThat(result.tariffRuleId()).isNotNull();
        assertThat(result).isEqualTo(new Rule(result.tariffRuleId(), version, expected.productType(), expected.cityId(),
                expected.currency(), expected.lowerBound(), expected.upperBound(), expected.markupRate()));
    }
    /** Проверяет одновременно статус и код production-ошибки, сохраняя различие неверного ввода и отсутствующей записи. */
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TariffApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status); assertThat(error.code()).isEqualTo(code);
        });
    }
}
