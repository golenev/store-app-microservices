package com.shop.store.pyramid.api_database;

import com.shop.store.codec.ShopCodec;
import com.shop.store.codec.ShopInputValidator;
import com.shop.store.controller.ShopController;
import com.shop.store.exception.ShopErrorHandler;
import com.shop.store.repository.InventoryRepository;
import com.shop.store.repository.CartRepository;
import com.shop.store.repository.SubmissionRepository;
import com.shop.store.repository.GoodsReceiptRepository;
import com.shop.store.service.GoodsReceiptService;
import com.shop.store.service.CartService;
import com.shop.store.service.SubmissionService;
import com.shop.store.service.SubmissionTransactionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
@EnableJpaRepositories(basePackages = "com.shop.store.repository.jpa")
@Import({GoodsReceiptRepository.class, GoodsReceiptService.class, ShopController.class, ShopErrorHandler.class, ShopCodec.class, ShopInputValidator.class, InventoryRepository.class, CartRepository.class, SubmissionRepository.class, CartService.class, SubmissionService.class, SubmissionTransactionService.class})
class StoreApiDatabaseConfig {
    /**
     * Включает такое же преобразование ошибок Hibernate в ошибки Spring, как production Spring Boot.
     * @return обработчик аннотации Repository для UNIQUE, CHECK и других ошибок сохранения
     */
    @Bean static PersistenceExceptionTranslationPostProcessor exceptionTranslation() {
        return new PersistenceExceptionTranslationPostProcessor();
    }
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
     * Создаёт Hibernate по существующей Flyway-схеме. Проверяет отображение сущностей,
     * не создаёт таблицы и не меняет их; каждую транзакцию обслуживает отдельный контекст.
     * @param source тестовая PostgreSQL после миграций
     * @return фабрика контекстов JPA
     */
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.shop.store.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "validate"));
        return factory;
    }

    /**
     * Подключает транзакции JPA к той же PostgreSQL. Тестовые вызовы фиксируются независимо;
     * REQUIRES_NEW оформления сохраняет собственную границу отката.
     * @param factory фабрика контекстов
     * @return менеджер транзакций JPA
     */
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
    /**
     * Создаёт преобразование Java-моделей и дат в JSON и обратно для запросов и ответов теста.
     *
     * @return настроенное преобразование Java-моделей и JSON
     */
    @Bean ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /**
     * Возвращает часы с фиксированным временем {@code 2026-10-09T12:00:00Z}. Даты изменений и ошибок можно
     * сравнивать с заранее известным значением.
     *
     * @return системные или фиксированные часы, заданные этой конфигурацией
     */
    @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC); }

}
