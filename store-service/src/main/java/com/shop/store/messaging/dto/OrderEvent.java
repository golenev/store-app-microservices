package com.shop.store.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие {@code OrderSubmitted} о принятой заявке. Повторная отправка использует те же идентификатор и
 * содержимое.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param eventType имя типа события
 * @param schemaVersion версия формата события
 * @param occurredAt момент события в UTC
 * @param storeId идентификатор магазина
 * @param payload принятая заявка с позициями и суммой на момент оформления
 */
public record OrderEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String storeId, OrderPayload payload) { }
