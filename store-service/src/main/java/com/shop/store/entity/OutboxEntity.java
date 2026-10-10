package com.shop.store.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;

/**
 * Отображает таблицу {@code store_outbox}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "store_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEntity {
    /** Неизменяемый идентификатор события. */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Принятая заявка события. */
    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    /** Магазин и ключ Kafka. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Неизменяемый JSON OrderSubmitted. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    /** PENDING или PUBLISHED. */
    @Column(name = "publication_status", nullable = false, length = 16)
    private String publicationStatus;

    /** Время подтверждения Kafka; до публикации null. */
    @Column(name = "published_at", nullable = true)
    private Instant publishedAt;

    /** Число попыток отправки. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** Время следующей доступной попытки. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** Последняя безопасная причина отказа или null. */
    @Column(name = "last_error", nullable = true, length = 500)
    private String lastError;

    /** Токен владельца фоновой попытки или null. */
    @Column(name = "lease_token", nullable = true)
    private UUID leaseToken;

    /** Срок владения фоновой попыткой или null. */
    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;

}
