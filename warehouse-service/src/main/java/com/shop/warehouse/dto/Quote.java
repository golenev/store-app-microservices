package com.shop.warehouse.dto;

import java.util.UUID;

/**
 * Ответ тарифного сервиса: наценка как доля, UUID и версия выбранного правила.
 *
 * @param markupRate наценка как доля закупочной цены; {@code 0.20} означает 20 процентов
 * @param tariffRuleId идентификатор тарифного правила
 * @param tariffVersion версия применённого тарифного правила
 */
public record Quote(String markupRate, UUID tariffRuleId, long tariffVersion) { }
