package com.shop.warehouse.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Ответ поставщику после подтверждения отправки Kafka. Сохранение и оприходование поставки выполняются
 * позднее, поэтому этот ответ их не подтверждает.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param storeId идентификатор магазина
 * @param deliveryId идентификатор поставки внутри магазина
 * @param publicationStatus состояние отправки события: {@code PENDING} или {@code PUBLISHED}
 * @param publishedAt время подтверждения отправки события в Kafka; до отправки может отсутствовать
 */
public record Published(UUID eventId, String storeId, String deliveryId,
                            String publicationStatus, Instant publishedAt) { }
