package com.shop.store.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/** Конверт OrderSubmitted; повторная физическая публикация сохраняет eventId и содержимое. */
public record OrderEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String storeId, OrderPayload payload) { }
