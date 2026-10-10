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

/** Реальные контроллеры, обработчики ошибок и SQL выбранного сервиса; Kafka, фоновые работники и соседние сервисы не запускаются. */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@Import({TariffRuleController.class, TariffErrorHandler.class, TariffRuleRepository.class})
class TariffApiDatabaseConfig {
    /** Подключает отдельную PostgreSQL Testcontainers и применяет все production-миграции без доступа к Compose-базам. */
    @Bean DataSource dataSource() {
        var postgres = TariffApiDatabaseTest.POSTGRES;
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        return source;
    }
    /** Предоставляет SQL-подготовку и независимое чтение зафиксированных строк. */
    @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    /** Создаёт настоящие транзакции production-сервисов; тест не скрывает результат общим rollback. */
    @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
    /** Поддерживает типизированные JSON и даты в подготовке и чтении ответа. */
    @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    /** Фиксирует время изменений и ошибок для точного сравнения сохранённых дат. */
    @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC); }

    /** Создаёт и закрывает настоящую фабрику проверки входных условий. */
    @Bean(destroyMethod="close") ValidatorFactory validation() { return Validation.buildDefaultValidatorFactory(); }
    /** Собирает настоящий сервис CRUD, который Spring оборачивает транзакционным прокси. */
    @Bean TariffRuleService rules(TariffRuleRepository repository, ValidatorFactory validation) { return new TariffRuleService(repository, validation.getValidator()); }
    /** Изолирует неиспользуемые ручки котировок: Redis и кеш не относятся к CRUD-набору. */
    @Bean TariffQuoteService quotes() { return org.mockito.Mockito.mock(TariffQuoteService.class); }

}
