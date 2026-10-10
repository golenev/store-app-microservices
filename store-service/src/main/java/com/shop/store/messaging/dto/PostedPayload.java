package com.shop.store.messaging.dto;

import java.time.Instant;
import java.util.List;

/**
 * Содержимое оприходованной поставки: строки, даты и порядок её первой приёмки в WAREHOUSE. Этот порядок
 * определяет, может ли поставка обновить цену остатка.
 *
 * @param deliveryId идентификатор поставки внутри магазина
 * @param deliverySequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
 * @param receivedAt время первой приёмки поставки в WAREHOUSE
 * @param postedAt время завершения расчёта и оприходования поставки в WAREHOUSE
 * @param items проверенные строки оприходованной поставки
 */
public record PostedPayload(String deliveryId, long deliverySequence, Instant receivedAt, Instant postedAt, List<PostedLine> items) { }
