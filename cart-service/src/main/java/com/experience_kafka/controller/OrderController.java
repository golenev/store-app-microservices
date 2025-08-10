package com.experience_kafka.controller;

import com.experience_kafka.entity.Order;
import com.experience_kafka.model.OrderRequest;
import com.experience_kafka.repository.OrderRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @PostMapping("/order")
    public void createOrder(@RequestBody OrderRequest request) {
        Order order = new Order(request.id(), request.createdAt(), request.orderSum(), request.items());
        orderRepository.save(order);
    }

}
