package com.shop.store.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Типизированная JPQL-проекция позиции корзины с текущей ценой и остатком.
 * @param stockItemId позиция остатка
 * @param productId продукт
 * @param shortName название
 * @param unitPrice текущая цена
 * @param availableQuantity доступный остаток
 * @param quantity итоговое количество в корзине
 */
public record CheckoutLine(UUID stockItemId, String productId, String shortName, BigDecimal unitPrice,
                           int availableQuantity, int quantity) { }
