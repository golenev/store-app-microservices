package com.shop.store.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Legacy single-product record; the new inventory and receipt history arrive in task 5.
 */
@Entity
@Table(name = "product")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {
    /** Supplier barcode used as the legacy primary key. */
    @Id
    @NotNull
    private Long barcodeId;

    /** Display name supplied in the raw Kafka product. */
    @NotBlank
    @Column(name = "short_name")
    private String shortName;

    /** Supplier description retained for the legacy catalog. */
    @NotBlank
    private String description;

    /** Incoming purchase price overwritten by legacy tariff calculation before persistence. */
    @NotNull
    @Positive
    private BigDecimal price;

    /** Shared available quantity; cart edits do not decrement it. */
    @Positive
    private int quantity;

    /** Legacy local timestamp; future event contracts use explicit UTC timestamps. */
    @Column(name = "added_at_tariffs")
    @NotNull
    private LocalDateTime addedAtTariffs = LocalDateTime.now();

    /** Selects the legacy food versus non-food percentage bands. */
    @JsonProperty("isFoodstuff")
    @Column(name = "is_foodstuff")
    private boolean isFoodstuff;
}

