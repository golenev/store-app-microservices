package com.shop.store.model;

import java.util.UUID;

/**
 * Заблокированное событие до назначения владельца отправки.
 * @param eventId идентификатор события
 * @param storeId магазин и ключ Kafka
 * @param payload неизменяемый JSON
 * @param attemptCount число завершённых или начатых попыток
 */
public record OutboxCandidate(UUID eventId, String storeId, String payload, int attemptCount) { }
