package com.shop.store.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Входящее событие {@code GoodsPosted}: идентификатор, версия формата, дата, магазин и содержимое
 * поставки. Идентификатор события помогает распознать повтор сообщения.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param eventType имя типа события
 * @param schemaVersion версия формата события
 * @param occurredAt момент события в UTC
 * @param storeId идентификатор магазина
 * @param payload проверенное содержимое оприходованной поставки с упорядоченными строками
 */
public record GoodsEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String storeId, PostedPayload payload) { }
