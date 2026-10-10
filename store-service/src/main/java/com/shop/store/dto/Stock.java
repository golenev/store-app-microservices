package com.shop.store.dto;

import java.util.UUID;

/**
 * Товар в каталоге магазина с текущей продажной ценой и доступным количеством. Нулевой остаток не
 * исключает товар из каталога.
 *
 * @param stockItemId идентификатор позиции остатка магазина
 * @param productId идентификатор продукта
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param shortName краткое название товара
 * @param description описание товара
 * @param unitPrice цена единицы товара строкой с двумя знаками после точки
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param availableQuantity доступное количество товара в магазине
 */
public record Stock(UUID stockItemId, String productId, String productType, String shortName, String description,
                        String unitPrice, String currency, int availableQuantity) { }
