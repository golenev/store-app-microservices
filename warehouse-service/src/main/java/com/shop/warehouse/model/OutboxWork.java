package com.shop.warehouse.model;

import java.util.UUID;

/**
 * Событие, выбранное из очереди отправки, с содержимым, идентификатором владельца и номером попытки.
 * Идентификатор владельца защищает запись результата от запоздалого отправителя.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param storeId идентификатор магазина
 * @param payload JSON события для очереди отправки
 * @param token UUID текущего владельца фоновой попытки
 * @param attemptCount число начатых попыток обработки
 */
public record OutboxWork(UUID eventId, String storeId, String payload, UUID token, long attemptCount) { }
