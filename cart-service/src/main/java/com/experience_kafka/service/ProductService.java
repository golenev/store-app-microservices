package com.experience_kafka.service;

import com.experience_kafka.controller.CartController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ProductService {

    private final RestTemplate restTemplate;

    @Value("${warehouse-service.url:http://localhost:6790}")
    private String warehouseServiceUrl;

    public ProductService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public CartController.ProductDto getProductById(Long id) {
        return restTemplate.getForObject(
                warehouseServiceUrl + "/api/v1/products/" + id,
                CartController.ProductDto.class
        );
    }
}
