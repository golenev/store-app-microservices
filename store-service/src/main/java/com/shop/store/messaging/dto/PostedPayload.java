package com.shop.store.messaging.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

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
public record PostedPayload(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String deliveryId,
        @Min(1) @Max(9007199254740991L) long deliverySequence,
        @NotNull Instant receivedAt,
        @NotNull Instant postedAt,
        @NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid PostedLine> items) { }
