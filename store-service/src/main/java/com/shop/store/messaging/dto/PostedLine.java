package com.shop.store.messaging.dto;

import java.util.UUID;

/**
 * Строка оприходованной поставки с положительным количеством, закупочной и продажной ценами и применённым
 * тарифом. По наценке можно проверить расчёт продажной цены.
 *
 * @param lineId идентификатор строки внутри поставки
 * @param productId идентификатор продукта
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param shortName краткое название товара
 * @param description описание товара
 * @param quantity количество единиц товара
 * @param purchasePrice закупочная цена строкой с двумя знаками после точки
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param markupRate наценка как доля закупочной цены; {@code 0.20} означает 20 процентов
 * @param tariffRuleId идентификатор тарифного правила
 * @param tariffVersion версия применённого тарифного правила
 * @param salePrice рассчитанная продажная цена строкой с двумя знаками после точки
 */
public record PostedLine(String lineId, String productId, String productType, String shortName, String description,
                             int quantity, String purchasePrice, String currency, String markupRate,
                             UUID tariffRuleId, long tariffVersion, String salePrice) { }
