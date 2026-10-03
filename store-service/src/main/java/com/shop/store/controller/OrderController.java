package com.shop.store.controller;

import com.shop.store.entity.Order;
import com.shop.store.model.OrderRequest;
import com.shop.store.repository.OrderRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderRepository orderRepository;

    /**
     * Receives the legacy order repository without performing persistence during construction.
     */
    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Saves the client-supplied legacy order snapshot without inventory debit, idempotency or outbox.
     */
    @PostMapping("/order")
    public void createOrder(@RequestBody OrderRequest request) {
        Order order = new Order(request.id(), request.createdAt(), request.orderSum(), request.items());
        orderRepository.save(order);
    }

}
