package com.shop.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StoreServiceApplication {
    /**
     * Запускает Spring Boot с параметрами args; компонентное сканирование охватывает слои под корневым пакетом
     * приложения.
     *
     * @param args аргументы запуска Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
