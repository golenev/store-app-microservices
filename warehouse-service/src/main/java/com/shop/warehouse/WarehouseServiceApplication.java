package com.shop.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка запуска WAREHOUSE: подключает компоненты поставок, HTTP API и обработку сообщений Kafka.
 */
@SpringBootApplication
@EnableScheduling
public class WarehouseServiceApplication {
    /**
     * Запускает Spring Boot с аргументами командной строки. Spring находит и подключает компоненты в корневом
     * пакете сервиса и его подпакетах.
     *
     * @param args аргументы командной строки для запуска Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
