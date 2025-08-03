package com.experience_kafka.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Общая сущность товара.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {
    @Id
    @NotNull
    private Long barcodeId;

    @NotBlank
    @Column(name = "short_name")
    private String shortName;

    @NotBlank
    private String description;

    @NotNull
    @Positive
    private BigDecimal price;

    @Positive
    private int quantity;

    @Column(name = "added_at_warehouse")
    @NotNull
    private LocalDateTime addedAtWarehouse = LocalDateTime.now();

    @JsonProperty("isFoodstuff")
    @Column(name = "is_foodstuff")
    private boolean isFoodstuff;
}

