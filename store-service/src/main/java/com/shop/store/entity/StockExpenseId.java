package com.shop.store.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** Составной первичный ключ таблицы {@code stock_expenses}; равенство определяется всеми его полями. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class StockExpenseId implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Принятая заявка. */
    private UUID submissionId;
    /** Списанная позиция остатка. */
    private UUID stockItemId;
}
