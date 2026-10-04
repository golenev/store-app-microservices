package com.shop.store.shop;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** STORE-local wire records; money stays decimal strings, cart identity is independent of users and threads. */
public final class ShopModels {
    /** Prevents construction of the protocol namespace. */
    private ShopModels() { }
    public record PostedLine(String lineId, String productId, String productType, String shortName, String description,
                             int quantity, String purchasePrice, String currency, String markupRate,
                             UUID tariffRuleId, long tariffVersion, String salePrice) { }
    public record PostedPayload(String deliveryId, long deliverySequence, Instant receivedAt, Instant postedAt, List<PostedLine> items) { }
    public record GoodsEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String storeId, PostedPayload payload) { }
    public record Decoded(GoodsEvent event, String fingerprint) { }
    public record Stock(UUID stockItemId, String productId, String productType, String shortName, String description,
                        String unitPrice, String currency, int availableQuantity) { }
    public record Catalog(String storeId, List<Stock> items) { }
    public record CartLine(UUID stockItemId, String productId, String shortName, int quantity, String unitPrice, String lineTotal) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cart(String storeId, UUID cartId, long version, String state, List<CartLine> items, String totalAmount, String currency, UUID submissionId) { }
    public record PutItem(int quantity, long expectedCartVersion) { }
    public record SubmitInput(long expectedCartVersion) { }
    public record OrderPayload(UUID submissionId, UUID cartId, Instant acceptedAt, List<CartLine> items, String totalAmount, String currency) { }
    public record OrderEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String storeId, OrderPayload payload) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Submission(String storeId, UUID submissionId, UUID cartId, UUID eventId, String publicationStatus, Instant acceptedAt, Instant publishedAt) { }
    public record OutboxWork(UUID eventId, String storeId, String payload, UUID leaseToken, int attemptCount) { }
    public record ErrorResponse(Instant timestamp, int status, String code, String message, String path) { }
}
