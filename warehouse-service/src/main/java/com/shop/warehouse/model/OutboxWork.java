package com.shop.warehouse.model;

import java.util.UUID;

/** Захваченное событие outbox; токен защищает запись результата устаревшим обработчиком. */
public record OutboxWork(UUID eventId, String storeId, String payload, UUID token, long attemptCount) { }
