package com.shop.warehouse.messaging.dto;

/**
 * Проверенная строка входящей поставки с количеством и закупочной ценой. Наценка и продажная цена ещё не
 * рассчитаны.
 *
 * @param lineId идентификатор строки внутри поставки
 * @param productId идентификатор продукта
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param shortName краткое название товара
 * @param description описание товара
 * @param quantity количество единиц товара
 * @param purchasePrice закупочная цена строкой с двумя знаками после точки
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 */
public record InputLine(String lineId, String productId, String productType, String shortName,
                            String description, int quantity, String purchasePrice, String currency) { }
