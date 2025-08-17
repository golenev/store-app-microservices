package com.experience_kafka.model;

import java.math.BigDecimal;

/**
 * Модель для фронта, возвращает состояние корзины
 * @param barcodeId штрихкод товара
 * @param shortName краткое название товара
 * @param price цена за единицу
 * @param quantity количество
 * @param total сумма
 */
public record CartView(Long barcodeId, String shortName, BigDecimal price, int quantity, BigDecimal total) {}

