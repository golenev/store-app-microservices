package com.tariffs.pyramid.api_database;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Снимок всех колонок правила, прочитанный напрямую из тестовой PostgreSQL. Ценовые границы и наценка
 * остаются точными десятичными числами для независимого сравнения с ответом API.
 *
 * @param id UUID тарифного правила
 * @param version версия тарифного правила
 * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
 * @param cityId идентификатор города, для которого выбирается тариф
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param lowerBound нижняя граница закупочной цены, включительно
 * @param upperBound верхняя граница закупочной цены, не включается; {@code null} снимает предел
 * @param markupRate наценка как доля закупочной цены; {@code 0.20} означает 20 процентов
 */
record PersistedTariffRule(UUID id, long version, String productType, String cityId, String currency,
                           BigDecimal lowerBound, BigDecimal upperBound, BigDecimal markupRate) { }
