package com.shop.warehouse.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Задаёт единые часы приложения для дат событий, сроков фоновых попыток и времени ошибок.
 */
@Configuration
public class ClockConfiguration {
    /**
     * Возвращает системные часы UTC с точностью до миллисекунды, совпадающей с точностью сохраняемых дат
     * PostgreSQL.
     *
     * @return системные или фиксированные часы, заданные этой конфигурацией
     */
    @Bean
    public static Clock warehouseClock() { return Clock.tickMillis(ZoneOffset.UTC); }
}
