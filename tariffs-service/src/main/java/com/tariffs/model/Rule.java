package com.tariffs.model;

import java.util.UUID;

/** Версионное правило тарифа с интервалом цен и дробной наценкой. */
public record Rule(UUID tariffRuleId, long version, String productType, String cityId,
                       String currency, String lowerBound, String upperBound, String markupRate) { }
