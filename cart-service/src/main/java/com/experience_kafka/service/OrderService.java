package com.experience_kafka.service;

import com.experience_kafka.entity.CartItem;
import com.experience_kafka.entity.OrderEntity;
import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.CartItemRepository;
import com.experience_kafka.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сервис оформления заказа.
 */
@Service
public class OrderService {
    private final CartItemRepository cartRepository;
    private final ProductService productService;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderService(CartItemRepository cartRepository, ProductService productService,
                        OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Создаёт заказ на основании содержимого корзины.
     */
    @Transactional
    public OrderEntity placeOrder() {
        List<CartItem> items = cartRepository.findAll();
        if (items.isEmpty()) {
            throw new IllegalStateException("Корзина пуста");
        }

        List<Map<String, Object>> itemsForJson = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (CartItem cartItem : items) {
            Product product = productService.getProductById(cartItem.getBarcodeId());
            BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
            int qty = cartItem.getQuantity();
            BigDecimal itemTotal = price.multiply(BigDecimal.valueOf(qty));
            total = total.add(itemTotal);

            itemsForJson.add(Map.of(
                    "barcodeId", product.getBarcodeId(),
                    "shortName", product.getShortName(),
                    "price", price,
                    "quantity", qty,
                    "total", itemTotal
            ));

            // уменьшаем количество товара на складе
            productService.decreaseQuantity(product.getBarcodeId(), qty);
        }

        String itemsJson;
        try {
            itemsJson = objectMapper.writeValueAsString(itemsForJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка сериализации корзины", e);
        }

        OrderEntity order = new OrderEntity();
        order.setCreatedAt(LocalDateTime.now());
        order.setTotal(total);
        order.setItems(itemsJson);

        OrderEntity saved = orderRepository.save(order);

        cartRepository.deleteAll();

        return saved;
    }
}

