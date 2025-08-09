package com.experience_kafka.service;

import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product getProductById(Long barcodeId) {
        return productRepository.findById(barcodeId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product not found with barcode: " + barcodeId));
    }

    /**
     * Уменьшает количество товара на складе на указанное число.
     */
    public void decreaseQuantity(Long barcodeId, int qty) {
        Product product = getProductById(barcodeId);
        int newQty = product.getQuantity() - qty;
        product.setQuantity(newQty);
        productRepository.save(product);
    }
}
