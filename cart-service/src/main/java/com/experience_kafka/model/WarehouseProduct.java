package com.experience_kafka.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Товары на складе.
 * Используется для передачи данных между сервисами.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseProduct {
    private Long id;
    private String description;
    private BigDecimal price;
    private LocalDateTime receivedAt;
}
