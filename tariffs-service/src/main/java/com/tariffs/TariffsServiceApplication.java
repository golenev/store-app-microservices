package com.tariffs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TariffsServiceApplication {
    /** Starts the tariff HTTP API, Flyway, Redis client and Moscow-midnight scheduler. */
    public static void main(String[] args) {
        SpringApplication.run(TariffsServiceApplication.class, args);
    }
}
