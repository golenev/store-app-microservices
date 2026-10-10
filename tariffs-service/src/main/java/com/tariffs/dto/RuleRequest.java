package com.tariffs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import static com.tariffs.validation.TariffPatterns.*;

/** Полная замена правила; явный null upperBound означает неограниченный верхний предел. */
public record RuleRequest(
            @NotBlank @Pattern(regexp = "FOOD|NON_FOOD") String productType,
            @NotBlank @Pattern(regexp = IDENTIFIER) String cityId,
            @NotBlank @Pattern(regexp = "RUB") String currency,
            @NotBlank @Pattern(regexp = MONEY) String lowerBound,
            @JsonProperty(value = "upperBound", required = true) @Pattern(regexp = MONEY) String upperBound,
            @NotBlank @Pattern(regexp = RATE) String markupRate) { }
