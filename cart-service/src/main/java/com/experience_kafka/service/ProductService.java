package com.experience_kafka.service;

import com.experience_kafka.controller.CartController;
import com.experience_kafka.repository.WarehouseProductRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class ProductService {
    private final WarehouseProductRepository warehouseProductRepository;

    public ProductService(WarehouseProductRepository warehouseProductRepository) {
        this.warehouseProductRepository = warehouseProductRepository;
    }

    public CartController.ProductDto getProductById(Long id) {
        return warehouseProductRepository.findById(id)
                .map(p -> new CartController.ProductDto(p.getId(), p.getDescription(), p.getPrice()))
                .orElseThrow(() -> new NoSuchElementException("Product not found with id: " + id));
    }
}
