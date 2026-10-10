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
 * Отображает таблицу {@code processed_events}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEventEntity {
    /** Обработанный идентификатор события. */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Магазин события. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Поставка события. */
    @Column(name = "delivery_id", nullable = false, length = 64)
    private String deliveryId;

    /** SHA-256 принятого содержимого. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

}
