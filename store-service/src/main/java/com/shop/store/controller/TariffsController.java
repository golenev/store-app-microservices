package com.shop.store.controller;

import com.shop.store.entity.Product;
import com.shop.store.repository.ProductRepository;
import com.shop.store.service.KafkaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TariffsController {

    @Autowired
    private KafkaService kafkaService;

    private final ProductRepository repository;

    /**
     * Reads all legacy products without reserving or mutating stock.
     */
    @GetMapping("/products")
    public List<Product> getAvailableProducts() {
        return repository.findAll();
    }

    /**
     * Publishes the supplied product to send-topic and returns the legacy text response without waiting for broker acknowledgement.
     */
    @PostMapping("/sendToKafka")
    public String sendMessage(@Valid @RequestBody Product product) {
        kafkaService.sendMessage("send-topic", product);
        return "Сообщение отправлено в Кафку";
    }
}
