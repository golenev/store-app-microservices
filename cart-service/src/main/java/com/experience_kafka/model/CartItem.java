package com.experience_kafka.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * СОхраняет в базу сущности товаров, находящихся в корзине
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class CartItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * Штрихкод товара, добавленного в корзину.
     */
    private Long barcodeId;
    private String description;
    private BigDecimal price;
    private int quantity;
    private LocalDateTime addedAt = LocalDateTime.now();
}
