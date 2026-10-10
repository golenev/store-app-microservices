package com.shop.store.dto;

import java.util.UUID;

/** Позиция каталога магазина с текущей ценой и доступным количеством, включая нулевой остаток. */
public record Stock(UUID stockItemId, String productId, String productType, String shortName, String description,
                        String unitPrice, String currency, int availableQuantity) { }
