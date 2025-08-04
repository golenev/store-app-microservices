package com.warehouse.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "tariffs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tariff {
    @Id
    @Column(name = "product_type", nullable = false)
    private String productType;

    @Column(name = "markup_coefficient", nullable = false)
    private BigDecimal markupCoefficient;
}
