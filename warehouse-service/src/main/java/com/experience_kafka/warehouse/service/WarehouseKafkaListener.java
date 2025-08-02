package com.experience_kafka.warehouse.service;

import com.experience_kafka.warehouse.model.WarehouseProduct;
import com.experience_kafka.warehouse.repository.WarehouseProductRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class WarehouseKafkaListener {
    private final WarehouseProductRepository repository;

    public WarehouseKafkaListener(WarehouseProductRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "send-topic", groupId = "warehouse-service")
    public void consume(WarehouseProduct product) {
        product.setPrice(product.getPrice().multiply(BigDecimal.valueOf(1.1)));
        product.setReceivedAt(LocalDateTime.now());
        repository.save(product);
    }
}
