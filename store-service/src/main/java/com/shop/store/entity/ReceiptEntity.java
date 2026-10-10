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
 * Отображает таблицу {@code stock_receipts}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "stock_receipts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ReceiptId.class)
public class ReceiptEntity {
    /** Магазин поставки. */
    @Id
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Идентификатор поставки. */
    @Id
    @Column(name = "delivery_id", nullable = false, length = 64)
    private String deliveryId;

    /** Неизменяемый порядок первой приёмки WAREHOUSE. */
    @Column(name = "delivery_sequence", nullable = false)
    private long deliverySequence;

    /** SHA-256 нормализованного содержимого. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    /** Время первой приёмки. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Время расчёта цены WAREHOUSE. */
    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    /** Время прихода STORE. */
    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    /** Неизменяемый JSON принятого события. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

}
