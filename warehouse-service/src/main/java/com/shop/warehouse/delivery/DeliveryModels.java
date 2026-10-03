package com.shop.warehouse.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Service-local wire and persisted-work records; monetary values never pass through floating point. */
public final class DeliveryModels {
    /** Prevents construction of the DTO namespace. */
    private DeliveryModels() { }
    public record InputLine(String lineId, String productId, String productType, String shortName,
                            String description, int quantity, String purchasePrice, String currency) { }
    public record Accepted(UUID eventId, String storeId, String deliveryId, JsonNode payload,
                           List<InputLine> items, String fingerprint, Failure rejection) { }
    public record Failure(String code, String message) { }
    public record Quote(String markupRate, UUID tariffRuleId, long tariffVersion) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String lineId, String productId, String productType, String shortName,
                       String description, int quantity, String purchasePrice, String currency,
                       String markupRate, UUID tariffRuleId, Long tariffVersion, String salePrice) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(String storeId, String deliveryId, long deliverySequence, String state,
                       Instant receivedAt, Instant postedAt, long attemptCount, Instant nextAttemptAt,
                       Failure lastError, List<Line> items, JsonNode rejectedPayload) { }
    public record GoodsPayload(String deliveryId, long deliverySequence, Instant receivedAt,
                               Instant postedAt, List<Line> items) { }
    public record GoodsEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                             String storeId, GoodsPayload payload) { }
    public record Published(UUID eventId, String storeId, String deliveryId,
                            String publicationStatus, Instant publishedAt) { }
    public record PricingWork(String storeId, String deliveryId, String cityId, UUID token,
                              long attemptCount, List<Line> items) { }
    public record OutboxWork(UUID eventId, String storeId, String payload, UUID token, long attemptCount) { }
    public record ErrorResponse(Instant timestamp, int status, String code, String message, String path) { }
}
