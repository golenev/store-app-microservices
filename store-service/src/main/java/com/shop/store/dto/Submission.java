package com.shop.store.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * Принятая заявка и состояние отправки её события. Время отправки появляется после подтверждения Kafka;
 * статус {@code PUBLISHED} не подтверждает оплату.
 *
 * @param storeId идентификатор магазина
 * @param submissionId идентификатор принятой заявки
 * @param cartId идентификатор корзины
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param publicationStatus состояние отправки события: {@code PENDING} или {@code PUBLISHED}
 * @param acceptedAt время принятия заявки на оформление
 * @param publishedAt время подтверждения отправки события в Kafka; до отправки может отсутствовать
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Submission(String storeId, UUID submissionId, UUID cartId, UUID eventId, String publicationStatus, Instant acceptedAt, Instant publishedAt) { }
