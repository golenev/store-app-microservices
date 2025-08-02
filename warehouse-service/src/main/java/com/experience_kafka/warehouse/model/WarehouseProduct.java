package com.experience_kafka.warehouse.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseProduct {
    @Id
    private Long id;
    private String description;
    private BigDecimal price;
    private LocalDateTime receivedAt;
}
