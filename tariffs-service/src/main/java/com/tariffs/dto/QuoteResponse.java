package com.tariffs.dto;

import java.util.UUID;

/** Дробная наценка выбранного правила и версия неизменяемого результата расчёта. */
public record QuoteResponse(String markupRate, UUID tariffRuleId, long tariffVersion) { }
