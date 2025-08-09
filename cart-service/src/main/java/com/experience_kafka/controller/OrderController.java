package com.experience_kafka.controller;

import com.experience_kafka.entity.Order;
import com.experience_kafka.model.CartView;
import com.experience_kafka.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
public class OrderController {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderController(OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/order")
    public void createOrder(@RequestBody OrderRequest request) throws JsonProcessingException {
        String itemsJson = objectMapper.writeValueAsString(request.items());
        Order order = new Order(request.id(), request.createdAt(), request.orderSum(), itemsJson);
        orderRepository.save(order);
    }

    public record OrderRequest(Long id, LocalDateTime createdAt, BigDecimal orderSum, List<CartView> items) {}
}
