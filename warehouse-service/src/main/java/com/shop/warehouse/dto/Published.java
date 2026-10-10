package com.shop.warehouse.dto;

import java.time.Instant;
import java.util.UUID;

/** Подтверждение отправки поставщиком в Kafka; не обещает приёмку или POSTED. */
public record Published(UUID eventId, String storeId, String deliveryId,
                            String publicationStatus, Instant publishedAt) { }
