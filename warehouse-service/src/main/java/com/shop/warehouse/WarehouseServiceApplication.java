package com.shop.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runtime entry point. Delivery consumers and APIs are implemented in task 4. */
@SpringBootApplication
public class WarehouseServiceApplication {
    /** Starts HTTP, database migrations and health probes using the supplied Spring arguments. */
    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
