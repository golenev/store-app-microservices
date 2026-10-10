package com.shop.store.dto;

import java.util.UUID;

/** Строка корзины с серверной ценой и точной суммой для указанного количества. */
public record CartLine(UUID stockItemId, String productId, String shortName, int quantity, String unitPrice, String lineTotal) { }
