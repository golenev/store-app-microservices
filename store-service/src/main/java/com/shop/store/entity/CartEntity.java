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
 * Отображает таблицу {@code carts}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartEntity {
    /** Идентификатор независимой корзины. */
    @Id
    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    /** Магазин корзины. */
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    /** Версия API; меняется явно под блокировкой, не является JPA @Version. */
    @Column(name = "version", nullable = false)
    private long version;

    /** OPEN или SUBMITTED. */
    @Column(name = "state", nullable = false, length = 16)
    private String state;

    /** Время создания корзины в UTC. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
