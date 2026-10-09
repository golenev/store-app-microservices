package com.shop.warehouse.pyramid.api_database;
import com.shop.warehouse.delivery.*;
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

/** Реальные контроллеры, обработчики ошибок и SQL выбранного сервиса; Kafka, фоновые работники и соседние сервисы не запускаются. */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@Import({DeliveryController.class, DeliveryErrorHandler.class, DeliveryCodec.class})
class WarehouseApiDatabaseConfig {
    /** Подключает отдельную PostgreSQL Testcontainers и применяет все production-миграции без доступа к Compose-базам. */
    @Bean DataSource dataSource() {
        var postgres = WarehouseApiDatabaseTest.POSTGRES;
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

    /** Создаёт настоящий транзакционный сервис поставок с управляемым временем и сроком аренды. */
    @Bean DeliveryStore deliveries(JdbcTemplate jdbc, DeliveryCodec codec, Clock clock) { return new DeliveryStore(jdbc, codec, clock, 30000); }
    /** Предоставляет только неиспользуемую зависимость конструктора; публикация и проверки Kafka отсутствуют. */
    @Bean KafkaTemplate<String,String> kafka() { return org.mockito.Mockito.mock(KafkaTemplate.class); }

}
