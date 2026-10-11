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
 * Отображает таблицу {@code cart_items}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(CartItemId.class)
public class CartItemEntity {
    /**
     * Ленивое чтение остатка по UUID для JPQL JOIN. Запись связи выполняется через stockItemId;
     * каскадных изменений остатка нет, принадлежность магазину дополнительно защищена внешним ключом БД.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_item_id", insertable = false, updatable = false)
    private InventoryEntity stock;

    /** Корзина позиции. */
    @Id
    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    /** Позиция остатка. */
    @Id
    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    /** Магазин корзины и остатка. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Итоговое количество товара в корзине. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

}
