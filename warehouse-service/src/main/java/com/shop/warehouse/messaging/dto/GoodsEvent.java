package com.shop.warehouse.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/** Конверт исходящего GoodsPosted, сохраняемый в outbox до публикации. */
public record GoodsEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                             String storeId, GoodsPayload payload) { }
