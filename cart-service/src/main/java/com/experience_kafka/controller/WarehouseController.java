package com.experience_kafka.controller;

import com.experience_kafka.model.WarehouseProduct;
import com.experience_kafka.repository.WarehouseProductRepository;
import com.experience_kafka.service.KafkaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
                .map(p -> new CartController.ProductDto(p.getId(), p.getDescription(), p.getPrice()))
                .toList();
    }


    // Автоматически генерируем ID на сервере (например, через AtomicLong или UUID)
    private final AtomicLong idGenerator = new AtomicLong(1);

    @PostMapping("/sendToKafka")
    public String sendMessage(@RequestBody WarehouseProduct product) {
        // Генерируем уникальный ID для продукта
        product.setId(idGenerator.getAndIncrement());
        // Отправляем объект в Kafka
        kafkaService.sendMessage("send-topic", product);
        return "Сообщение отправлено в Кафку";
    }
}