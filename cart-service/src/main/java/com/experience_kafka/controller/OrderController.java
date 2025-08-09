package com.experience_kafka.controller;

import com.experience_kafka.entity.OrderEntity;
import com.experience_kafka.service.OrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Контроллер для оформления заказа.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Map<String, Long> createOrder() {
        OrderEntity order = orderService.placeOrder();
        return Map.of("orderId", order.getId());
    }
}

