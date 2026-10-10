package com.shop.store.model;

import java.util.UUID;

/**
 * Событие, выбранное из очереди отправки, с содержимым, идентификатором владельца и номером попытки.
 * Владение позволяет отклонить запоздалое изменение от прежнего отправителя.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param storeId идентификатор магазина
 * @param payload JSON события для очереди отправки
 * @param leaseToken UUID текущего владельца отправки
 * @param attemptCount число начатых попыток обработки
 */
public record OutboxWork(UUID eventId, String storeId, String payload, UUID leaseToken, int attemptCount) { }
