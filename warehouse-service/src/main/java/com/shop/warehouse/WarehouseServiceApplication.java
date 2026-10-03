package com.shop.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts durable Kafka reception, automatic pricing, outbox publication and delivery status HTTP. */
@SpringBootApplication
@EnableScheduling
public class WarehouseServiceApplication {
    /** Starts HTTP, database migrations and health probes using the supplied Spring arguments. */
    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
