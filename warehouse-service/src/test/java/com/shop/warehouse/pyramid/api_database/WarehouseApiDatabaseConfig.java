package com.shop.warehouse.pyramid.api_database;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.controller.DeliveryController;
import com.shop.warehouse.exception.DeliveryErrorHandler;
import com.shop.warehouse.service.DeliveryService;
import com.shop.warehouse.service.SupplierDeliveryService;
import com.shop.warehouse.messaging.DeliveryPublisher;
import com.shop.warehouse.repository.DeliveryRepository;
import org.springframework.kafka.core.KafkaTemplate;

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
@Import({DeliveryController.class, DeliveryErrorHandler.class, DeliveryCodec.class, DeliveryRepository.class, SupplierDeliveryService.class, DeliveryPublisher.class})
class WarehouseApiDatabaseConfig {
    /**
     * Подключает отдельный PostgreSQL в контейнере Testcontainers и применяет миграции сервиса. Базы Docker
     * Compose и пользовательские данные не используются.
     *
     * @return подключение к подготовленной тестовой БД с применёнными миграциями
     */
    @Bean DataSource dataSource() {
        var postgres = WarehouseApiDatabaseTest.POSTGRES;
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
     * Создаёт сервис поставок с фиксированными часами и сроком владения 30 000 миллисекунд. Spring подключает
     * транзакции к вызовам его методов.
     *
     * @param repository чтение и запись поставок и очереди событий
     * @param codec проверка событий поставок и преобразование сохранённых моделей
     * @param clock часы для дат операций и сроков фоновых попыток
     * @return сервис поставок с фиксированными часами
     */
    @Bean DeliveryService deliveries(DeliveryRepository repository, DeliveryCodec codec, Clock clock) { return new DeliveryService(repository, codec, clock, 30000); }
    /**
     * Подставляет Mockito-объект отправки Kafka для сборки контекста. В этих тестах отправка не вызывается и
     * её результат не проверяется.
     *
     * @return подставная отправка Kafka для сборки тестового контекста
     */
    @Bean KafkaTemplate<String,String> kafka() { return org.mockito.Mockito.mock(KafkaTemplate.class); }

}
