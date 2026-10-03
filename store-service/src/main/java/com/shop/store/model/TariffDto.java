package com.shop.store.model;

import java.math.BigDecimal;

public class TariffDto {
    private String productType;
    private BigDecimal markupCoefficient;

    /**
     * Returns the legacy category used to select a price band.
     */
    public String getProductType() {
        return productType;
    }

    /**
     * Assigns the legacy category deserialized from the tariff-list response.
     */
    public void setProductType(String productType) {
        this.productType = productType;
    }

    /**
     * Returns percentage markup, rather than the fractional rate of the future quote API.
     */
    public BigDecimal getMarkupCoefficient() {
        return markupCoefficient;
    }

    /**
     * Assigns the percentage markup from the legacy tariff-list response.
     */
    public void setMarkupCoefficient(BigDecimal markupCoefficient) {
        this.markupCoefficient = markupCoefficient;
    }

    /**
     * Returns category and percentage for diagnostics without changing the DTO.
     */
    @Override
    public String toString() {
        return "TariffDto{" +
                "productType='" + productType + '\'' +
                ", markupCoefficient=" + markupCoefficient +
                '}';
    }
}

