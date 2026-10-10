package com.tariffs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import static com.tariffs.validation.TariffPatterns.*;

/**
 * Полный набор условий для создания или замены правила. Нижняя граница цены включается, верхняя
 * исключается; явно переданный {@code upperBound = null} снимает верхний предел.
 *
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param cityId идентификатор города, для которого выбирается тариф
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param lowerBound нижняя граница закупочной цены, включительно
 * @param upperBound верхняя граница закупочной цены, не включается; {@code null} снимает предел
 * @param markupRate наценка как доля закупочной цены; {@code 0.20} означает 20 процентов
 */
public record RuleRequest(
            @NotBlank @Pattern(regexp = "FOOD|NON_FOOD") String productType,
            @NotBlank @Pattern(regexp = IDENTIFIER) String cityId,
            @NotBlank @Pattern(regexp = "RUB") String currency,
            @NotBlank @Pattern(regexp = MONEY) String lowerBound,
            @JsonProperty(value = "upperBound", required = true) @Pattern(regexp = MONEY) String upperBound,
            @NotBlank @Pattern(regexp = RATE) String markupRate) { }
