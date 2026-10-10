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

/**
 * Проверяет девять сценариев создания, чтения, замены и удаления тарифных правил в отдельном PostgreSQL в
 * контейнере. Использует миграции, репозиторий и транзакционный сервис приложения.
 */
@Tag("database") @Testcontainers @SpringJUnitConfig(TariffCrudPostgresTest.Database.class)
class TariffCrudPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private TariffRuleService service;
    @Autowired private JdbcTemplate jdbc;
    private final Set<UUID> created = new HashSet<>();

    /**
     * Собирает только компоненты работы с правилами и проверки полей. HTTP-сервер, Redis и другие сервисы не
     * запускает.
     */
    @org.springframework.context.annotation.Configuration @EnableTransactionManagement
    static class Database {
        /**
         * Подключает отдельный PostgreSQL в контейнере и применяет миграции сервиса. Пользовательские базы не
         * используются.
         *
         * @return подключение к подготовленной тестовой БД с применёнными миграциями
         */
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            return source;
        }
        /**
         * Создаёт выполнение SQL в тестовой PostgreSQL. Запросы обрабатывает движок БД, а не подставная
         * зависимость.
         *
         * @param source подключение к отдельной тестовой PostgreSQL
         * @return выполнение SQL через переданное подключение к БД
         */
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        /**
         * Подключает управление транзакциями к тестовой БД для методов сервиса.
         *
         * @param source подключение к отдельной тестовой PostgreSQL
         * @return управление транзакциями через переданное подключение к БД
         */
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        /**
         * Создаёт репозиторий приложения, который выполняет SQL в тестовой БД.
         *
         * @param jdbc выполнение SQL с участием в текущей транзакции Spring
         * @return репозиторий приложения с доступом к тестовой БД
         */
        @Bean TariffRuleRepository repository(JdbcTemplate jdbc) { return new TariffRuleRepository(jdbc); }
        /**
         * Создаёт фабрику проверки ограничений полей. Spring закроет её при завершении контекста.
         *
         * @return фабрика проверки ограничений полей
         */
        @Bean(destroyMethod="close") ValidatorFactory validation() { return Validation.buildDefaultValidatorFactory(); }
        /**
         * Создаёт сервис правил с репозиторием и проверкой полей. Spring подключает транзакции к вызовам его
         * методов.
         *
         * @param repository чтение и запись тарифных правил
         * @param validation фабрика проверки ограничений полей
         * @return сервис правил с подключёнными зависимостями
         */
        @Bean TariffRuleService service(TariffRuleRepository repository, ValidatorFactory validation) {
            return new TariffRuleService(repository, validation.getValidator());
        }
    }

    /**
     * Удаляет только правила с UUID, созданными текущим тестом. Начальные данные миграций и чужие записи
     * сохраняются.
     */
    @AfterEach void cleanup() { created.forEach(id -> jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?", id)); }

    /**
     * TAR-CRUD-001-DB. Создаёт правило: {@code NON_FOOD}, {@code RUB}, границы 0.00–500.00, наценка 0.20.
     * Проверяет все поля и версию 1, затем читает правило снова после сохранения транзакции сервиса. Общей
     * транзакции теста с последующей отменой нет.
     */
    @Test @DisplayName("TAR-CRUD-001-DB: создание правила версии 1")
    void createsRule() {
        Rule result = create();
        assertRule(result, 1, input());
        assertThat(service.get(result.tariffRuleId())).isEqualTo(result);
    }

    /**
     * TAR-CRUD-002-DB. Создаёт правило версии 1 и читает его по полученному UUID. Проверяет совпадение всех
     * полей; чтение выполняет SQL-запрос в тестовой БД.
     */
    @Test @DisplayName("TAR-CRUD-002-DB: чтение созданного правила")
    void readsRule() {
        Rule existing = create();
        assertThat(service.get(existing.tariffRuleId())).isEqualTo(existing);
    }

    /**
     * TAR-CRUD-003-DB. Заменяет правило версии 1: {@code FOOD}, нижняя граница 5.00, верхнего предела нет,
     * наценка 0.30. После сохранения транзакции проверяет прежний UUID, версию 2 и все новые поля повторным
     * чтением. Выполняются блокировка и UPDATE в PostgreSQL; Redis не используется.
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
     * TAR-CRUD-004-DB. Создаёт и удаляет правило. После сохранения удаления проверяет {@code NOT_FOUND} и HTTP
     * 404 при чтении того же UUID через репозиторий приложения.
     */
    @Test @DisplayName("TAR-CRUD-004-DB: удаление и отсутствие при чтении")
    void deletesRule() {
        UUID id = create().tariffRuleId();
        service.delete(id);
        assertError(() -> service.get(id), 404, "NOT_FOUND");
    }

    /**
     * TAR-CRUD-005-DB. Читает неизвестный UUID и проверяет {@code NOT_FOUND} и HTTP 404. Запись не должна
     * создаваться.
     */
    @Test @DisplayName("TAR-CRUD-005-DB: чтение неизвестного UUID")
    void rejectsMissingRead() { assertError(() -> service.get(UUID.randomUUID()), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-006-DB. Передаёт допустимую замену для отсутствующего UUID. Проверяет {@code NOT_FOUND} и HTTP
     * 404; повторное чтение также должно сообщить об отсутствии правила.
     */
    @Test @DisplayName("TAR-CRUD-006-DB: замена неизвестного UUID")
    void rejectsMissingUpdate() {
        UUID id = UUID.randomUUID();
        assertError(() -> service.update(id, replacement()), 404, "NOT_FOUND");
        assertError(() -> service.get(id), 404, "NOT_FOUND");
    }

    /**
     * TAR-CRUD-007-DB. Удаляет отсутствующий UUID и проверяет {@code NOT_FOUND} и HTTP 404.
     */
    @Test @DisplayName("TAR-CRUD-007-DB: удаление неизвестного UUID")
    void rejectsMissingDelete() { assertError(() -> service.delete(UUID.randomUUID()), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-008-DB. Пытается создать правило с равными границами 100.00. Проверяет {@code VALIDATION_ERROR}
     * и HTTP 400; число правил собственного города остаётся прежним.
     */
    @Test @DisplayName("TAR-CRUD-008-DB: неверное создание ничего не сохраняет")
    void rejectsInvalidCreate() {
        int before = countOwnRules();
        assertError(() -> service.create(invalid()), 400, "VALIDATION_ERROR");
        assertThat(countOwnRules()).isEqualTo(before);
    }

    /**
     * TAR-CRUD-009-DB. Создаёт правило версии 1 и пытается заменить его условиями с равными границами.
     * Проверяет {@code VALIDATION_ERROR} и HTTP 400, затем повторным чтением сверяет все прежние поля и
     * версию.
     */
    @Test @DisplayName("TAR-CRUD-009-DB: неверная замена сохраняет исходное правило")
    void rejectsInvalidUpdate() {
        Rule existing = create();
        assertError(() -> service.update(existing.tariffRuleId(), invalid()), 400, "VALIDATION_ERROR");
        assertThat(service.get(existing.tariffRuleId())).isEqualTo(existing);
    }

    /**
     * Создаёт исходное правило через сервис приложения и запоминает UUID для удаления только этой записи после
     * теста.
     *
     * @return правило с UUID, версией и полным набором условий
     */
    private Rule create() { Rule result = service.create(input()); created.add(result.tariffRuleId()); return result; }
    /**
     * Возвращает исходные условия правила, совпадающие с условиями сценариев Mockito и HTTP.
     *
     * @return допустимые исходные условия правила
     */
    private RuleRequest input() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "0.00", "500.00", "0.20"); }
    /**
     * Возвращает полный набор новых условий для замены. Значение {@code null} верхней границы означает
     * отсутствие предела цены.
     *
     * @return новые условия полной замены правила
     */
    private RuleRequest replacement() { return new RuleRequest("FOOD", "PYRAMID", "RUB", "5.00", null, "0.30"); }
    /**
     * Возвращает равные границы цены для проверки отказа до записи в БД.
     *
     * @return неверные условия с равными границами цены
     */
    private RuleRequest invalid() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "100.00", "100.00", "0.20"); }
    /**
     * Считает только правила города текущего теста. Начальные правила {@code MOSCOW} и {@code SPB} в результат
     * не входят.
     *
     * @return число правил города текущего теста
     */
    private int countOwnRules() { return jdbc.queryForObject("SELECT count(*) FROM tariff_rules WHERE city_id='PYRAMID'", Integer.class); }
    /**
     * Сравнивает UUID, версию и каждое поле правила с заданными ожиданиями, чтобы обнаружить неполную замену.
     *
     * @param result фактически полученное правило для сравнения с ожиданием
     * @param version версия тарифного правила
     * @param expected ожидаемые условия правила
     */
    private void assertRule(Rule result, long version, RuleRequest expected) {
        assertThat(result.tariffRuleId()).isNotNull();
        assertThat(result).isEqualTo(new Rule(result.tariffRuleId(), version, expected.productType(), expected.cityId(),
                expected.currency(), expected.lowerBound(), expected.upperBound(), expected.markupRate()));
    }
    /**
     * Вызывает операцию, которая должна завершиться ошибкой, и сравнивает HTTP-статус и код исключения с
     * ожидаемыми.
     *
     * @param action операция, которая должна вызвать проверяемое исключение
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     */
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TariffApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status); assertThat(error.code()).isEqualTo(code);
        });
    }
}
