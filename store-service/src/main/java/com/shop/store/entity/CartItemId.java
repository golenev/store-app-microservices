package com.shop.store.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** Составной первичный ключ таблицы {@code cart_items}; равенство определяется всеми его полями. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CartItemId implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Корзина позиции. */
    private UUID cartId;
    /** Позиция остатка. */
    private UUID stockItemId;
}
