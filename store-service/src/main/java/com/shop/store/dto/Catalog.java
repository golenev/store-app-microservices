package com.shop.store.dto;

import java.util.List;

/** Каталог одного магазина; каждая позиция соответствует одному productId. */
public record Catalog(String storeId, List<Stock> items) { }
