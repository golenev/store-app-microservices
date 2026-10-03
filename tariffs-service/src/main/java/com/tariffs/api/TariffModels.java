package com.tariffs.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire DTOs for the v1 tariff API; decimal values remain strings, never floating-point numbers. */
public final class TariffModels {
    public static final String MONEY = "^(0|[1-9][0-9]{0,25})\\.[0-9]{2}$";
    public static final String RATE = "^(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}$";
    public static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$";

    /** Prevents construction of the DTO namespace. */
    private TariffModels() { }

    /** A quote selects exactly one rule by product, city, currency and a positive purchase price. */
    public record QuoteRequest(
            @NotBlank @Pattern(regexp = "FOOD|NON_FOOD") String productType,
            @NotBlank @Pattern(regexp = MONEY) String purchasePrice,
            @NotBlank @Pattern(regexp = "RUB") String currency,
            @NotBlank @Pattern(regexp = IDENTIFIER) String cityId) { }

    /** Full rule replacement; explicit null upperBound means an unbounded interval. */
    public record RuleRequest(
            @NotBlank @Pattern(regexp = "FOOD|NON_FOOD") String productType,
            @NotBlank @Pattern(regexp = IDENTIFIER) String cityId,
            @NotBlank @Pattern(regexp = "RUB") String currency,
            @NotBlank @Pattern(regexp = MONEY) String lowerBound,
            @JsonProperty(value = "upperBound", required = true) @Pattern(regexp = MONEY) String upperBound,
            @NotBlank @Pattern(regexp = RATE) String markupRate) { }

    public record Rule(UUID tariffRuleId, long version, String productType, String cityId,
                       String currency, String lowerBound, String upperBound, String markupRate) { }
    public record RulesResponse(List<Rule> items) { }
    public record QuoteResponse(String markupRate, UUID tariffRuleId, long tariffVersion) { }
    public record CacheResetResponse(String cache, Instant resetAt) { }
    public record ErrorDetail(String field, String message) { }
    public record ErrorResponse(Instant timestamp, int status, String code, String message,
                                String path, List<ErrorDetail> details) { }
}
