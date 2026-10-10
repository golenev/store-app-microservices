package com.shop.store.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/** Конверт входящего GoodsPosted; eventId и storeId участвуют в защите повторной обработки. */
public record GoodsEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String storeId, PostedPayload payload) { }
