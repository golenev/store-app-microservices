package com.tariffs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import static com.tariffs.validation.TariffPatterns.*;

/**
 * Условия выбора тарифа: тип товара, город, валюта и положительная закупочная цена. Проверяются перед
 * чтением кеша и БД.
 *
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param purchasePrice закупочная цена строкой с двумя знаками после точки
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param cityId идентификатор города, для которого выбирается тариф
 */
public record QuoteRequest(
            @NotBlank @Pattern(regexp = "FOOD|NON_FOOD") String productType,
            @NotBlank @Pattern(regexp = MONEY) String purchasePrice,
            @NotBlank @Pattern(regexp = "RUB") String currency,
            @NotBlank @Pattern(regexp = IDENTIFIER) String cityId) { }
