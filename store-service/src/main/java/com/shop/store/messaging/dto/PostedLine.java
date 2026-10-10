package com.shop.store.messaging.dto;

import java.util.UUID;

/** Строка GoodsPosted с ценой и версией тарифа; количество строго положительное. */
public record PostedLine(String lineId, String productId, String productType, String shortName, String description,
                             int quantity, String purchasePrice, String currency, String markupRate,
                             UUID tariffRuleId, long tariffVersion, String salePrice) { }
