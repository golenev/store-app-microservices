package com.shop.store.messaging.dto;

import java.time.Instant;
import java.util.List;

/** Содержимое оприходованной поставки с неизменяемым порядком первой приёмки WAREHOUSE. */
public record PostedPayload(String deliveryId, long deliverySequence, Instant receivedAt, Instant postedAt, List<PostedLine> items) { }
