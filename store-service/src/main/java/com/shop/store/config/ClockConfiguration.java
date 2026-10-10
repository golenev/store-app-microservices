package com.shop.store.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Общие часы приложения для событий, сроков захвата и HTTP-ошибок. */
@Configuration
public class ClockConfiguration {
    /**
     * Возвращает UTC-часы с точностью миллисекунд, согласованной с сохранёнными датами PostgreSQL.
     */
    @Bean
    public static Clock shopClock() { return Clock.tickMillis(ZoneOffset.UTC); }
}
