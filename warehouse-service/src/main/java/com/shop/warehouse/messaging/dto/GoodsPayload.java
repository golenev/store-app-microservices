package com.shop.warehouse.messaging.dto;

import com.shop.warehouse.dto.Line;

import java.time.Instant;
import java.util.List;

/**
 * Содержимое оприходованной поставки: даты, рассчитанные строки и неизменяемый порядок первой приёмки в
 * магазине.
 *
 * @param deliveryId идентификатор поставки внутри магазина
 * @param deliverySequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
 * @param receivedAt время первой приёмки поставки в WAREHOUSE
 * @param postedAt время завершения расчёта и оприходования поставки в WAREHOUSE
 * @param items строки поставки с количеством, ценами и данными тарифа
 */
public record GoodsPayload(String deliveryId, long deliverySequence, Instant receivedAt,
                               Instant postedAt, List<Line> items) { }
