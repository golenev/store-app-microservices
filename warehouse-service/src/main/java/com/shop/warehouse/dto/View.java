package com.shop.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Состояние поставки для диагностики: даты, попытки расчёта, последняя ошибка и строки. Для состояния
 * {@code REJECTED} включает исходное содержимое отклонённой поставки.
 *
 * @param storeId идентификатор магазина
 * @param deliveryId идентификатор поставки внутри магазина
 * @param deliverySequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
 * @param state состояние поставки: {@code WAITING_PRICING}, {@code POSTED} или {@code REJECTED}
 * @param receivedAt время первой приёмки поставки в WAREHOUSE
 * @param postedAt время завершения расчёта и оприходования поставки в WAREHOUSE
 * @param attemptCount число начатых попыток обработки
 * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
 * @param lastError код и пояснение неудачного расчёта или отклонения поставки
 * @param items строки поставки с количеством, ценами и данными тарифа
 * @param rejectedPayload исходное содержимое отклонённой поставки; в других состояниях отсутствует
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record View(String storeId, String deliveryId, long deliverySequence, String state,
                       Instant receivedAt, Instant postedAt, long attemptCount, Instant nextAttemptAt,
                       Failure lastError, List<Line> items, JsonNode rejectedPayload) { }
