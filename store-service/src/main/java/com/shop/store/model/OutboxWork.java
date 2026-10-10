package com.shop.store.model;

import java.util.UUID;

/** Захваченное событие outbox с токеном владельца и числом попыток публикации. */
public record OutboxWork(UUID eventId, String storeId, String payload, UUID leaseToken, int attemptCount) { }
