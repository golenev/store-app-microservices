package com.shop.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.shop.contracts.ContractSupport.JSON;
import static com.shop.contracts.ContractSupport.read;
import static com.shop.contracts.ContractSupport.schema;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the target event wire format without starting any Spring service. */
class EventContractTest {
    /** Test-local envelope; future services own their production DTOs independently. */
    private record Envelope(String eventId, String eventType, int schemaVersion,
                            Instant occurredAt, String storeId, JsonNode payload) {
    }

    /** Explicit supplier DTO keeps purchasePrice textual across deserialization/serialization. */
    private record DeliveryItem(String lineId, String productId, String productType, String shortName,
                                String description, int quantity, String purchasePrice, String currency) {
    }

    /** Supplier payload groups its uniquely identified lines under a delivery identifier. */
    private record DeliveryPayload(String deliveryId, List<DeliveryItem> items) {
    }

    /** Each envelope retains its identifiers, textual payload and UTC instant on a DTO round trip. */
    @ParameterizedTest
    @ValueSource(strings = {"delivery-received", "goods-posted", "order-submitted"})
    void eventEnvelopeRoundTripPreservesWireContract(String fixture) throws Exception {
        JsonNode original = read("examples/events/" + fixture + ".json");
        Envelope dto = JSON.treeToValue(original, Envelope.class);
        JsonNode restored = JSON.valueToTree(dto);

        assertEquals(original, restored);
        assertTrue(schema(dto.eventType()).validate(restored).isEmpty());
        assertEquals(Instant.parse(original.path("occurredAt").asText()), dto.occurredAt());
    }

    /** Typed supplier lines preserve price scale and UTF-8 metadata without binary floating point. */
    @Test
    void supplierDtoPreservesDecimalStringAndProductMetadata() throws Exception {
        JsonNode event = read("examples/events/delivery-received.json");
        DeliveryPayload payload = JSON.treeToValue(event.path("payload"), DeliveryPayload.class);

        assertEquals("100.00", payload.items().getFirst().purchasePrice());
        assertEquals(new BigDecimal("100.00"), new BigDecimal(payload.items().getFirst().purchasePrice()));
        assertEquals("Мыло", payload.items().getFirst().shortName());
        assertEquals(event.path("payload"), JSON.valueToTree(payload));
    }

    /** A price beyond JavaScript's safe integer range retains every digit as a decimal string. */
    @Test
    void preservesPriceBeyondFloatingPointIntegerPrecision() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        ((ObjectNode) event.at("/payload/items/0")).put("purchasePrice", "9007199254740993.01");
        DeliveryPayload payload = JSON.treeToValue(event.path("payload"), DeliveryPayload.class);

        assertEquals("9007199254740993.01", payload.items().getFirst().purchasePrice());
        assertTrue(schema("DeliveryReceived").validate(event).isEmpty());
        assertEquals(event.path("payload"), JSON.valueToTree(payload));
    }

    /** Unknown schema versions are not accepted as v1 even when the payload otherwise matches. */
    @Test
    void rejectsUnknownSchemaVersion() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        event.put("schemaVersion", 2);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** A posted payload cannot be mislabeled as a supplier or order event. */
    @Test
    void rejectsWrongEventType() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/goods-posted.json");
        event.put("eventType", "DeliveryReceived");
        assertFalse(schema("GoodsPosted").validate(event).isEmpty());
    }

    /** Numeric JSON prices are rejected so clients cannot silently introduce floating-point money. */
    @Test
    void rejectsNumericPurchasePrice() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        ((ObjectNode) event.at("/payload/items/0")).put("purchasePrice", 100.00);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Negative, zero and incorrectly scaled prices are invalid purchase amounts. */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00", "100", "100.0", "100.001", "01.00", "NaN"})
    void rejectsInvalidPurchasePrice(String price) throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        ((ObjectNode) event.at("/payload/items/0")).put("purchasePrice", price);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** A supplier cannot deliver zero or negative units; stock availability has a separate rule. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveDeliveryQuantity(int quantity) throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        ((ObjectNode) event.at("/payload/items/0")).put("quantity", quantity);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** A missing deliverySequence cannot be replaced by Kafka arrival time for price selection. */
    @Test
    void rejectsPostedEventWithoutImmutableSequence() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/goods-posted.json");
        ((ObjectNode) event.path("payload")).remove("deliverySequence");
        assertFalse(schema("GoodsPosted").validate(event).isEmpty());
    }

    /** UUID validation is active rather than merely documented in the schema. */
    @Test
    void rejectsMalformedEventIdentifier() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        event.put("eventId", "not-a-uuid");
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Invalid dates and non-UTC timestamps cannot enter a v1 event envelope. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30T10:00:00Z", "2026-10-03T10:00:00", "2026-10-03T10:00:00+03:00"})
    void rejectsInvalidOrNonUtcTimestamp(String timestamp) throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        event.put("occurredAt", timestamp);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Explicit v1 schemas reject unexpected fields instead of accidentally accepting another contract. */
    @Test
    void rejectsUnknownEnvelopeField() throws Exception {
        ObjectNode event = (ObjectNode) read("examples/events/delivery-received.json");
        event.put("stockItemId", "supplier-must-not-create-store-inventory");
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Malformed and duplicate-key JSON is rejected before schema/business validation. */
    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"schemaVersion\":1,\"schemaVersion\":2}", "{} {}"})
    void rejectsMalformedOrAmbiguousJson(String input) {
        assertThrows(JsonProcessingException.class, () -> JSON.readTree(input));
    }
}
