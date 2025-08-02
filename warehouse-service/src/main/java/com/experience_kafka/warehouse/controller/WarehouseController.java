package com.experience_kafka.warehouse.controller;

import com.experience_kafka.warehouse.model.WarehouseProduct;
import com.experience_kafka.warehouse.repository.WarehouseProductRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class WarehouseController {

    private final WarehouseProductRepository repository;

    public WarehouseController(WarehouseProductRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/products")
    public List<WarehouseProduct> getProducts() {
        return repository.findAll();
    }

    @GetMapping("/products/{id}")
    public WarehouseProduct getProduct(@PathVariable Long id) {
        return repository.findById(id).orElse(null);
    }
}
