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
 * Отображает таблицу {@code incoming_goods_diagnostics}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "incoming_goods_diagnostics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(IncomingDiagnosticId.class)
public class IncomingDiagnosticEntity {
    /** Топик исходного сообщения. */
    @Id
    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    /** Раздел Kafka. */
    @Id
    @Column(name = "partition_id", nullable = false)
    private int partitionId;

    /** Позиция сообщения в разделе. */
    @Id
    @Column(name = "kafka_offset", nullable = false)
    private long kafkaOffset;

    /** Время сохранения диагностики. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Код отказа. */
    @Column(name = "code", nullable = false, length = 64)
    private String code;

    /** Безопасное пояснение отказа. */
    @Column(name = "message", nullable = false, length = 500)
    private String message;

    /** Исходное сообщение или null. */
    @Column(name = "raw_message", nullable = true, columnDefinition = "text")
    private String rawMessage;

}
