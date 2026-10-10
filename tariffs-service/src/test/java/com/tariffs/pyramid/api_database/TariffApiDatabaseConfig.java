package com.tariffs.pyramid.api_database;

import com.tariffs.exception.TariffErrorHandler;
import com.tariffs.controller.TariffRuleController;
import com.tariffs.repository.TariffRuleRepository;
import com.tariffs.service.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import java.time.*;

/**
 * Собирает контроллеры, обработку ошибок и доступ к БД для API-тестов одного сервиса. Kafka, фоновые
 * задачи и соседние сервисы не запускает.
 */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@Import({TariffRuleController.class, TariffErrorHandler.class, TariffRuleRepository.class})
class TariffApiDatabaseConfig {
    /**
     * Подключает отдельный PostgreSQL в контейнере Testcontainers и применяет миграции сервиса. Базы Docker
     * Compose и пользовательские данные не используются.
     *
     * @return подключение к подготовленной тестовой БД с применёнными миграциями
     */
    @Bean DataSource dataSource() {
        var postgres = TariffApiDatabaseTest.POSTGRES;
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        return source;
    }
    /**
     * Создаёт средство выполнения SQL для подготовки данных и независимого чтения записей после запроса API.
     *
     * @param source подключение к отдельной тестовой PostgreSQL
     * @return выполнение SQL через переданное подключение к БД
     */
    @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    /**
     * Подключает управление транзакциями сервиса к тестовой БД. Тесты читают уже сохранённый результат; общей
     * транзакции с отменой всех записей после теста нет.
     *
     * @param source подключение к отдельной тестовой PostgreSQL
     * @return управление транзакциями через переданное подключение к БД
     */
    @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
    /**
     * Создаёт преобразование Java-моделей и дат в JSON и обратно для запросов и ответов теста.
     *
     * @return настроенное преобразование Java-моделей и JSON
     */
    @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    /**
     * Возвращает часы с фиксированным временем {@code 2026-10-09T12:00:00Z}. Даты изменений и ошибок можно
     * сравнивать с заранее известным значением.
     *
     * @return системные или фиксированные часы, заданные этой конфигурацией
     */
    @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC); }

    /**
     * Создаёт фабрику проверки ограничений полей. Spring закроет её при завершении тестового контекста.
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
    @Bean TariffRuleService rules(TariffRuleRepository repository, ValidatorFactory validation) { return new TariffRuleService(repository, validation.getValidator()); }
    /**
     * Подставляет Mockito-объект расчёта наценки для сборки контроллера. Эти тесты проверяют управление
     * правилами; расчёт и Redis не вызываются.
     *
     * @return подставной сервис расчётов для неиспользуемых методов контроллера
     */
    @Bean TariffQuoteService quotes() { return org.mockito.Mockito.mock(TariffQuoteService.class); }

}
