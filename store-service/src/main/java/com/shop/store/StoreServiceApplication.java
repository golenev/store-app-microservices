package com.shop.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Точка запуска сервиса: подключает компоненты приложения и его HTTP API. */
@SpringBootApplication
@EnableScheduling
public class StoreServiceApplication {
    /**
     * Запускает Spring Boot с аргументами командной строки. Spring находит и подключает компоненты в корневом
     * пакете сервиса и его подпакетах.
     *
     * @param args аргументы командной строки для запуска Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
