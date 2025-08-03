package com.experience_kafka.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Товары на складе.
 * Если товары есть, они отображаются в списке продуктов на http://localhost:6789/products.html
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseProduct {
    /**
     * Уникальный штрихкод товара. Используется как идентификатор.
     */
    @Id
    @NotNull
    @Positive
    private Long barcodeId;

    @NotBlank
    private String description;

    @NotNull
    @Positive
    private BigDecimal price;

    /**
     * Признак, является ли товар пищевым.
     */
    @JsonProperty("isFoodstuff")
    private boolean foodstuff;

    /**
     * Время поступления товара на склад.
     */
    @NotNull
    private OffsetDateTime arrivalTime;

    /**
     * Количество единиц товара, доставленных на склад.
     */
    @Positive
    private int quantity;
}
