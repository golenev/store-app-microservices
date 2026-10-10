package com.shop.store.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** Состояние независимой корзины; принятая корзина содержит неизменяемый снимок оформления. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Cart(String storeId, UUID cartId, long version, String state, List<CartLine> items, String totalAmount, String currency, UUID submissionId) { }
