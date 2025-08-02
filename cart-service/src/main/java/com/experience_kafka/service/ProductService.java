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

    public CartController.ProductDto getProductById(Long barcodeId) {
        return warehouseProductRepository.findById(barcodeId)
                .map(p -> new CartController.ProductDto(
                        p.getBarcodeId(),
                        p.getDescription(),
                        p.getPrice(),
                        p.isFoodstuff(),
                        p.getArrivalTime(),
                        p.getQuantity()))
                .orElseThrow(() -> new NoSuchElementException(
                        "Product not found with barcode: " + barcodeId));
    }
}
