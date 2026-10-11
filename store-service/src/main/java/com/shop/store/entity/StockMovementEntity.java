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
 * Отображает таблицу {@code stock_movements}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(StockMovementId.class)
public class StockMovementEntity {
    /** Магазин движения. */
    @Id
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Поставка движения. */
    @Id
    @Column(name = "delivery_id", nullable = false, length = 64)
    private String deliveryId;

    /** Строка поставки. */
    @Id
    @Column(name = "line_id", nullable = false, length = 64)
    private String lineId;

    /** Позиция увеличенного остатка. */
    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    /** Количество прихода. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Цена данной поставки. */
    @Column(name = "unit_price", nullable = false, precision = 28, scale = 2)
    private BigDecimal unitPrice;

    /** Время сохранения прихода. */
    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

}
