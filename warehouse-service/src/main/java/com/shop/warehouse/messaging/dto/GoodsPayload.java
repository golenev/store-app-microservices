package com.shop.warehouse.messaging.dto;

import com.shop.warehouse.dto.Line;

import java.time.Instant;
import java.util.List;

/** Содержимое GoodsPosted с неизменяемым порядком приёмки и рассчитанными строками. */
public record GoodsPayload(String deliveryId, long deliverySequence, Instant receivedAt,
                               Instant postedAt, List<Line> items) { }
