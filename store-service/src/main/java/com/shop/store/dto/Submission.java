package com.shop.store.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Принятая операция и состояние публикации; PUBLISHED подтверждает Kafka, а не оплату. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Submission(String storeId, UUID submissionId, UUID cartId, UUID eventId, String publicationStatus, Instant acceptedAt, Instant publishedAt) { }
