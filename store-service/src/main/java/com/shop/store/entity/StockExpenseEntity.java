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
 * Отображает таблицу {@code stock_expenses}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "stock_expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(StockExpenseId.class)
public class StockExpenseEntity {
    /** Принятая заявка. */
    @Id
    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    /** Списанная позиция остатка. */
    @Id
    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    /** Магазин расхода. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Количество списанного товара. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Цена на момент оформления. */
    @Column(name = "unit_price", nullable = false, precision = 28, scale = 2)
    private BigDecimal unitPrice;

    /** Время принятия заявки. */
    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

}
