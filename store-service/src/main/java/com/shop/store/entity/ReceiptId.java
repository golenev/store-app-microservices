package com.shop.store.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** Составной первичный ключ таблицы {@code stock_receipts}; равенство определяется всеми его полями. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ReceiptId implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Магазин поставки. */
    private String storeId;
    /** Идентификатор поставки. */
    private String deliveryId;
}
