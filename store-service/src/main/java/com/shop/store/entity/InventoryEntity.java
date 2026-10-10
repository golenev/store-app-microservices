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
 * Отображает таблицу {@code inventory}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryEntity {
    /** Идентификатор позиции остатка. */
    @Id
    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    /** Магазин, владеющий остатком. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Идентификатор продукта внутри магазина. */
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    /** Тип товара FOOD или NON_FOOD. */
    @Column(name = "product_type", nullable = false, length = 8)
    private String productType;

    /** Актуальное название товара. */
    @Column(name = "short_name", nullable = false, length = 255)
    private String shortName;

    /** Актуальное описание товара. */
    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    /** Цена всего доступного остатка с двумя знаками после точки. */
    @Column(name = "unit_price", nullable = false, precision = 28, scale = 2)
    private BigDecimal unitPrice;

    /** Валюта RUB. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Доступное количество без резервирования корзинами. */
    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    /** Порядок поставки, определившей текущую цену. */
    @Column(name = "last_delivery_sequence", nullable = false)
    private long lastDeliverySequence;

}
