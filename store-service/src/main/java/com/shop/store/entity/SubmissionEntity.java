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
 * Отображает таблицу {@code submissions}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmissionEntity {
    /** Идентификатор принятой заявки. */
    @Id
    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    /** Магазин заявки. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Однократно оформленная корзина. */
    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    /** Ключ повтора внутри магазина. */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** SHA-256 исходных параметров оформления. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    /** Исходная версия принятого запроса. */
    @Column(name = "expected_cart_version", nullable = false)
    private long expectedCartVersion;

    /** Неизменяемый идентификатор OrderSubmitted. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Время принятия заявки. */
    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    /** Неизменяемый JSON оформленной корзины. */
    @Column(name = "cart_snapshot", nullable = false, columnDefinition = "text")
    private String cartSnapshot;

}
