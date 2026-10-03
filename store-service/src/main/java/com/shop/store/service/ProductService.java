package com.shop.store.service;

import com.shop.store.entity.Product;
import com.shop.store.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    /**
     * Receives the product repository used for barcode lookups.
     */
    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Returns the product for the supplied barcode or throws NoSuchElementException with its identifier.
     */
    public Product getProductById(Long barcodeId) {
        return productRepository.findById(barcodeId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product not found with barcode: " + barcodeId));
    }
}
