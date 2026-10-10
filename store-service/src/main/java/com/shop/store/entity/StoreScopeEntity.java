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
 * Отображает таблицу {@code store_scopes}, созданную Flyway. Поля и ограничения БД сохраняют исходный контракт.
 * Сущность используется внутри транзакции; наружу сервис возвращает DTO. Lombok создаёт доступ к полям
 * и конструкторы без бизнес-проверок; сохранение выполняет репозиторий.
 */
@Entity
@Table(name = "store_scopes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreScopeEntity {
    /** Идентификатор магазина. */
    @Id
    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

}
