package com.shop.store.messaging.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;

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
public record GoodsEvent(
        @NotNull UUID eventId,
        @NotNull @Pattern(regexp = "GoodsPosted") String eventType,
        @Min(1) @Max(1) int schemaVersion,
        @NotNull Instant occurredAt,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String storeId,
        @NotNull @Valid PostedPayload payload) { }
