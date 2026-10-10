package com.shop.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Запускает сервис с миграциями и обработкой согласованных HTTP/Kafka-контрактов. */
@SpringBootApplication
@EnableScheduling
public class WarehouseServiceApplication {
    /**
     * Запускает Spring Boot с параметрами args; компонентное сканирование охватывает слои под корневым пакетом
     * приложения.
     *
     * @param args аргументы запуска Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
