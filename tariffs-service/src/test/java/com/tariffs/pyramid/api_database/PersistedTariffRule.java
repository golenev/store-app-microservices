package com.tariffs.pyramid.api_database;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Полный снимок строки тарифного правила без преобразования денежных значений в строки.
 * @param id идентификатор строки
 * @param version версия условий
 * @param productType тип товаров
 * @param cityId город действия условий
 * @param currency валюта цены
 * @param lowerBound включённая нижняя граница
 * @param upperBound исключённая верхняя граница либо отсутствие предела
 * @param markupRate дробная наценка с точностью SQL
 */
record PersistedTariffRule(UUID id, long version, String productType, String cityId, String currency,
                           BigDecimal lowerBound, BigDecimal upperBound, BigDecimal markupRate) { }
