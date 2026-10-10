package com.shop.store.pyramid.api_database;

import com.shop.store.codec.ShopCodec;
import com.shop.store.codec.ShopInputValidator;
import com.shop.store.controller.ShopController;
import com.shop.store.exception.ShopErrorHandler;
import com.shop.store.repository.InventoryRepository;
import com.shop.store.repository.CartRepository;
import com.shop.store.repository.SubmissionRepository;
import com.shop.store.service.CartService;
import com.shop.store.service.SubmissionService;
import com.shop.store.service.SubmissionTransactionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import javax.sql.DataSource;
import java.time.*;

/**
 * Собирает контроллеры, обработку ошибок и доступ к БД для API-тестов одного сервиса. Kafka, фоновые
 * задачи и соседние сервисы не запускает.
 */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@Import({ShopController.class, ShopErrorHandler.class, ShopCodec.class, ShopInputValidator.class, InventoryRepository.class, CartRepository.class, SubmissionRepository.class, CartService.class, SubmissionService.class, SubmissionTransactionService.class})
class StoreApiDatabaseConfig {
    /**
     * Подключает настоящую проверку ограничений DTO в тестовом MVC-контексте.
     * @return валидатор, ресурсы которого освобождает Spring при закрытии контекста
     */
    @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }

    /**
     * Подключает отдельный PostgreSQL в контейнере Testcontainers и применяет миграции сервиса. Базы Docker
     * Compose и пользовательские данные не используются.
     *
     * @return подключение к подготовленной тестовой БД с применёнными миграциями
     */
    @Bean DataSource dataSource() {
        var postgres = StoreApiDatabaseTest.POSTGRES;
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

}
