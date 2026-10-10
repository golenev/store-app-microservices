package com.shop.store.dto;

import java.util.List;

/**
 * Каталог одного магазина. Каждая позиция описывает отдельный продукт и его текущий остаток.
 *
 * @param storeId идентификатор магазина
 * @param items товары каталога с ценами и доступным количеством
 */
public record Catalog(String storeId, List<Stock> items) { }
