package com.tariffs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import static com.tariffs.validation.TariffPatterns.*;

/** Параметры выбора тарифа по типу продукта, городу, валюте и положительной закупочной цене. */
public record QuoteRequest(
            @NotBlank @Pattern(regexp = "FOOD|NON_FOOD") String productType,
            @NotBlank @Pattern(regexp = MONEY) String purchasePrice,
            @NotBlank @Pattern(regexp = "RUB") String currency,
            @NotBlank @Pattern(regexp = IDENTIFIER) String cityId) { }
