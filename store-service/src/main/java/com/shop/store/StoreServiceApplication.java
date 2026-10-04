package com.shop.store;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StoreServiceApplication {
    /**
     * Starts STORE with Flyway, GoodsPosted ingress, scoped APIs and automatic recovery of accepted outbox events.
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
