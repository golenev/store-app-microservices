package com.shop.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Legacy global-cart row. Independent store/user carts are introduced in task 5.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cart")
public class CartItem {
    /** Product barcode shared by all users of the legacy cart. */
    @Id
    @NotNull
    private Long barcodeId;

    /** Positive quantity requested in the global cart; this does not reserve stock. */
    @Positive
    private int quantity;
}

