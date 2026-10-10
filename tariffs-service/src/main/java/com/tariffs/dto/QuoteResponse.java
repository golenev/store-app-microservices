package com.tariffs.dto;

import java.util.UUID;

/**
 * Результат выбора тарифа: наценка, UUID и версия правила. Наценка задаётся долей: например, {@code 0.20}
 * означает 20 процентов.
 *
 * @param markupRate наценка как доля закупочной цены; {@code 0.20} означает 20 процентов
 * @param tariffRuleId идентификатор тарифного правила
 * @param tariffVersion версия применённого тарифного правила
 */
public record QuoteResponse(String markupRate, UUID tariffRuleId, long tariffVersion) { }
