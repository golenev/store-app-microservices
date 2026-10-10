package com.shop.warehouse.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Исходящее событие {@code GoodsPosted} об оприходованной поставке. Сохраняется в очереди БД до отправки в
 * Kafka.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param eventType имя типа события
 * @param schemaVersion версия формата события
 * @param occurredAt момент события в UTC
 * @param storeId идентификатор магазина
 * @param payload оприходованная поставка с датами, порядком приёмки и рассчитанными строками
 */
public record GoodsEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                             String storeId, GoodsPayload payload) { }
