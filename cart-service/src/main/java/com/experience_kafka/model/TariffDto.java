package com.experience_kafka.model;

import java.math.BigDecimal;

public class TariffDto {
    private String productType;
    private BigDecimal markupCoefficient;

    public String getProductType() {
        return productType;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public BigDecimal getMarkupCoefficient() {
        return markupCoefficient;
    }

    public void setMarkupCoefficient(BigDecimal markupCoefficient) {
        this.markupCoefficient = markupCoefficient;
    }

    @Override
    public String toString() {
        return "TariffDto{" +
                "productType='" + productType + '\'' +
                ", markupCoefficient=" + markupCoefficient +
                '}';
    }
}

