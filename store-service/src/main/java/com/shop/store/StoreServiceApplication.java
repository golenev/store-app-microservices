package com.shop.store;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StoreServiceApplication {
    /**
     * Starts STORE with Flyway, the GoodsPosted consumer and independent catalog/cart APIs.
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
