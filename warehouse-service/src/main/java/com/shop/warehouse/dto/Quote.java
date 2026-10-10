package com.shop.warehouse.dto;

import java.util.UUID;

/** Ответ TARIFFS с дробной наценкой, идентификатором правила и его версией. */
public record Quote(String markupRate, UUID tariffRuleId, long tariffVersion) { }
