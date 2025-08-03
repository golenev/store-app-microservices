package com.experience_kafka.controller;

import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.ProductRepository;
import com.experience_kafka.service.KafkaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WarehouseController {

    @Autowired
    private KafkaService kafkaService;

    private final ProductRepository repository;

    @GetMapping("/products")
    public List<Product> getAvailableProducts() {
        return repository.findAll();
    }

    @PostMapping("/sendToKafka")
    public String sendMessage(@Valid @RequestBody Product product) {
        kafkaService.sendMessage("send-topic", product);
        return "Сообщение отправлено в Кафку";
    }
}
