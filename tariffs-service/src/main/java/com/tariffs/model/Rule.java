package com.tariffs.model;

import java.util.UUID;

/**
 * Тарифное правило с UUID, версией, условиями выбора и наценкой. Диапазон включает нижнюю границу цены и
 * исключает верхнюю; отсутствие верхней границы снимает предел.
 *
 * @param tariffRuleId идентификатор тарифного правила
 * @param version версия тарифного правила
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param cityId идентификатор города, для которого выбирается тариф
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param lowerBound нижняя граница закупочной цены, включительно
 * @param upperBound верхняя граница закупочной цены, не включается; {@code null} снимает предел
 * @param markupRate наценка как доля закупочной цены; {@code 0.20} означает 20 процентов
 */
public record Rule(UUID tariffRuleId, long version, String productType, String cityId,
                       String currency, String lowerBound, String upperBound, String markupRate) { }
