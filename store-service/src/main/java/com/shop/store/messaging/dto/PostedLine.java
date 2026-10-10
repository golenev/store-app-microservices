package com.shop.store.messaging.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
public record PostedLine(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String lineId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String productId,
        @NotNull @Pattern(regexp = "FOOD|NON_FOOD") String productType,
        @NotNull String shortName,
        @NotNull String description,
        @Min(1) int quantity,
        @NotNull String purchasePrice,
        @NotNull @Pattern(regexp = "RUB") String currency,
        @NotNull @Pattern(regexp = "(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}") String markupRate,
        @NotNull UUID tariffRuleId,
        @Min(1) @Max(9007199254740991L) long tariffVersion,
        @NotNull String salePrice) { }
