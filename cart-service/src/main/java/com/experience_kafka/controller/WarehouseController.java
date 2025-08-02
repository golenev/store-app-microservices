package com.experience_kafka.controller;

import com.experience_kafka.model.WarehouseProduct;
import com.experience_kafka.repository.WarehouseProductRepository;
import com.experience_kafka.service.KafkaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WarehouseController {

    @Autowired
    private KafkaService kafkaService;

    private final WarehouseProductRepository repository;

    @GetMapping("/products")
    public List<CartController.ProductDto> getAvailableProducts() {
        return repository.findAll().stream()
                .map(p -> new CartController.ProductDto(
                        p.getBarcodeId(),
                        p.getDescription(),
                        p.getPrice(),
                        p.isFoodstuff(),
                        p.getArrivalTime(),
                        p.getQuantity()))
                .toList();
    }

    @PostMapping("/sendToKafka")
    public String sendMessage(@Valid @RequestBody WarehouseProduct product) {
        // Отправляем объект в Kafka
        kafkaService.sendMessage("send-topic", product);
        return "Сообщение отправлено в Кафку";
    }
}