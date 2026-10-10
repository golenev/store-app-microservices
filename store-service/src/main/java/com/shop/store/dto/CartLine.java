package com.shop.store.dto;

import java.util.UUID;

/**
 * Товар в корзине с количеством, ценой за единицу и суммой позиции. Цену определяет сервер; денежные
 * значения записаны строками с двумя знаками после точки.
 *
 * @param stockItemId идентификатор позиции остатка магазина
 * @param productId идентификатор продукта
 * @param shortName краткое название товара
 * @param quantity количество единиц товара
 * @param unitPrice цена единицы товара строкой с двумя знаками после точки
 * @param lineTotal сумма позиции: количество, умноженное на цену единицы
 */
public record CartLine(UUID stockItemId, String productId, String shortName, int quantity, String unitPrice, String lineTotal) { }
