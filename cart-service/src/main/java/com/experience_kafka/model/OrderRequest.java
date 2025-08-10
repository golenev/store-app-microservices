package com.experience_kafka.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Запрос на создание заказа, содержащий идентификатор, время создания,
 * итоговую сумму и список товаров в корзине.
 */
public record OrderRequest(Long id, LocalDateTime createdAt, BigDecimal orderSum,
                           List<CartView> items) {
}
