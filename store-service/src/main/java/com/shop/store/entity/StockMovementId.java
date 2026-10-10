package com.shop.store.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** Составной первичный ключ таблицы {@code stock_movements}; равенство определяется всеми его полями. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class StockMovementId implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Магазин движения. */
    private String storeId;
    /** Поставка движения. */
    private String deliveryId;
    /** Строка поставки. */
    private String lineId;
}
