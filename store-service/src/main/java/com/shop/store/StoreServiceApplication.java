package com.shop.store;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StoreServiceApplication {
    /**
     * Starts STORE with the supplied Spring arguments, including migrations and the legacy listener.
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
