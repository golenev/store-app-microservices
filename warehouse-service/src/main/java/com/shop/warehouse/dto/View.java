package com.shop.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Диагностическое состояние поставки магазина; REJECTED содержит исходный разобранный payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record View(String storeId, String deliveryId, long deliverySequence, String state,
                       Instant receivedAt, Instant postedAt, long attemptCount, Instant nextAttemptAt,
                       Failure lastError, List<Line> items, JsonNode rejectedPayload) { }
