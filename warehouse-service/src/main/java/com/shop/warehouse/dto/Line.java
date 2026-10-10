package com.shop.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Строка поставки; поля расчёта отсутствуют до завершения оприходования. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Line(String lineId, String productId, String productType, String shortName,
                       String description, int quantity, String purchasePrice, String currency,
                       String markupRate, UUID tariffRuleId, Long tariffVersion, String salePrice) { }
