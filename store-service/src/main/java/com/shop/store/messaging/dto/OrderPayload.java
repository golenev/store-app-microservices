package com.shop.store.messaging.dto;

import com.shop.store.dto.CartLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Неизменяемое содержимое принятой заявки, сохраняемое вместе со списанием остатка. */
public record OrderPayload(UUID submissionId, UUID cartId, Instant acceptedAt, List<CartLine> items, String totalAmount, String currency) { }
