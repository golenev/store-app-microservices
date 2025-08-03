package com.experience_kafka.model;

import java.math.BigDecimal;

/**
 * Модель для фронта, возвращает состояние корзины
 * @param shortName
 * @param price
 * @param quantity
 * @param total
 */
public record CartView(String shortName, BigDecimal price, int quantity, BigDecimal total) {}

