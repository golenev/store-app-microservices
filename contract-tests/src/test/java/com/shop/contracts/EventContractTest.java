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
import static com.shop.contracts.ContractSupport.schema;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет формат явно заданных событий и тестовую сериализацию без запуска сервисов или Kafka. */
class EventContractTest {
    /** Тестовая оболочка события; не используется как модель работающих сервисов. */
    private record Envelope(String eventId, String eventType, int schemaVersion,
                            Instant occurredAt, String storeId, JsonNode payload) {
    }

    /** Тестовая позиция поставки сохраняет purchasePrice строкой при чтении и записи JSON. */
    private record DeliveryItem(String lineId, String productId, String productType, String shortName,
                                String description, int quantity, String purchasePrice, String currency) {
    }

    /** Тестовые данные поставки объединяют идентификатор поставки и список позиций. */
    private record DeliveryPayload(String deliveryId, List<DeliveryItem> items) {
    }

    /** Преобразует явно заданный DeliveryReceived в тестовую модель и обратно; JSON, идентификаторы и время UTC сохраняются. */
    @Test
    void deliveryReceivedEnvelopeRoundTripPreservesWireContract() throws Exception {
        JsonNode original = JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        Envelope dto = JSON.treeToValue(original, Envelope.class);
        JsonNode restored = JSON.valueToTree(dto);

        assertEquals(original, restored);
        assertTrue(schema(dto.eventType()).validate(restored).isEmpty());
        assertEquals(Instant.parse(original.path("occurredAt").asText()), dto.occurredAt());
    }

    /** Преобразует явно заданный GoodsPosted в тестовую модель и обратно; JSON, идентификаторы и время UTC сохраняются. */
    @Test
    void goodsPostedEnvelopeRoundTripPreservesWireContract() throws Exception {
        JsonNode original = JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000002",
                  "eventType": "GoodsPosted",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:02Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "deliverySequence": 1,
                    "receivedAt": "2026-10-03T10:00:01Z",
                    "postedAt": "2026-10-03T10:00:02Z",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB",
                        "markupRate": "0.20",
                        "tariffRuleId": "b3000000-0000-4000-8000-000000000001",
                        "tariffVersion": 1,
                        "salePrice": "120.00"
                      }
                    ]
                  }
                }
                """);
        Envelope dto = JSON.treeToValue(original, Envelope.class);
        JsonNode restored = JSON.valueToTree(dto);

        assertEquals(original, restored);
        assertTrue(schema(dto.eventType()).validate(restored).isEmpty());
        assertEquals(Instant.parse(original.path("occurredAt").asText()), dto.occurredAt());
    }

    /** Преобразует явно заданный OrderSubmitted в тестовую модель и обратно; JSON, идентификаторы и время UTC сохраняются. */
    @Test
    void orderSubmittedEnvelopeRoundTripPreservesWireContract() throws Exception {
        JsonNode original = JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000003",
                  "eventType": "OrderSubmitted",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:03Z",
                  "storeId": "S-1",
                  "payload": {
                    "submissionId": "b6000000-0000-4000-8000-000000000001",
                    "cartId": "b5000000-0000-4000-8000-000000000001",
                    "acceptedAt": "2026-10-03T10:00:03Z",
                    "items": [
                      {
                        "stockItemId": "b4000000-0000-4000-8000-000000000001",
                        "productId": "P-1",
                        "shortName": "Мыло",
                        "quantity": 3,
                        "unitPrice": "120.00",
                        "lineTotal": "360.00"
                      }
                    ],
                    "totalAmount": "360.00",
                    "currency": "RUB"
                  }
                }
                """);
        Envelope dto = JSON.treeToValue(original, Envelope.class);
        JsonNode restored = JSON.valueToTree(dto);

        assertEquals(original, restored);
        assertTrue(schema(dto.eventType()).validate(restored).isEmpty());
        assertEquals(Instant.parse(original.path("occurredAt").asText()), dto.occurredAt());
    }

    /** Преобразует пример поставки в тестовую модель и обратно; денежная строка и русское название сохраняются. */
    @Test
    void supplierDtoPreservesDecimalStringAndProductMetadata() throws Exception {
        JsonNode event = JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        DeliveryPayload payload = JSON.treeToValue(event.path("payload"), DeliveryPayload.class);

        assertEquals("100.00", payload.items().getFirst().purchasePrice());
        assertEquals(new BigDecimal("100.00"), new BigDecimal(payload.items().getFirst().purchasePrice()));
        assertEquals("Мыло", payload.items().getFirst().shortName());
        assertEquals(event.path("payload"), JSON.valueToTree(payload));
    }

    /** Подставляет большую денежную строку в поставку; схема принимает её, а тестовая модель сохраняет все цифры. */
    @Test
    void preservesPriceBeyondFloatingPointIntegerPrecision() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        ((ObjectNode) event.at("/payload/items/0")).put("purchasePrice", "9007199254740993.01");
        DeliveryPayload payload = JSON.treeToValue(event.path("payload"), DeliveryPayload.class);

        assertEquals("9007199254740993.01", payload.items().getFirst().purchasePrice());
        assertTrue(schema("DeliveryReceived").validate(event).isEmpty());
        assertEquals(event.path("payload"), JSON.valueToTree(payload));
    }

    /** После замены schemaVersion на 2 схема v1 отклоняет событие поставки. */
    @Test
    void rejectsUnknownSchemaVersion() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        event.put("schemaVersion", 2);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** После замены типа GoodsPosted на DeliveryReceived схема GoodsPosted отклоняет событие. */
    @Test
    void rejectsWrongEventType() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000002",
                  "eventType": "GoodsPosted",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:02Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "deliverySequence": 1,
                    "receivedAt": "2026-10-03T10:00:01Z",
                    "postedAt": "2026-10-03T10:00:02Z",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB",
                        "markupRate": "0.20",
                        "tariffRuleId": "b3000000-0000-4000-8000-000000000001",
                        "tariffVersion": 1,
                        "salePrice": "120.00"
                      }
                    ]
                  }
                }
                """);
        event.put("eventType", "DeliveryReceived");
        assertFalse(schema("GoodsPosted").validate(event).isEmpty());
    }

    /** После замены денежной строки числом схема отклоняет позицию поставки. */
    @Test
    void rejectsNumericPurchasePrice() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        ((ObjectNode) event.at("/payload/items/0")).put("purchasePrice", 100.00);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Подставляет переданную некорректную цену в поставку; схема отклоняет ноль, отрицательные значения и неверный формат. */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00", "100", "100.0", "100.001", "01.00", "NaN"})
    void rejectsInvalidPurchasePrice(String price) throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        ((ObjectNode) event.at("/payload/items/0")).put("purchasePrice", price);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Подставляет переданное нулевое или отрицательное количество; схема отклоняет поставку. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveDeliveryQuantity(int quantity) throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        ((ObjectNode) event.at("/payload/items/0")).put("quantity", quantity);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** После удаления deliverySequence схема отклоняет GoodsPosted без порядка первой приёмки. */
    @Test
    void rejectsPostedEventWithoutImmutableSequence() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000002",
                  "eventType": "GoodsPosted",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:02Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "deliverySequence": 1,
                    "receivedAt": "2026-10-03T10:00:01Z",
                    "postedAt": "2026-10-03T10:00:02Z",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB",
                        "markupRate": "0.20",
                        "tariffRuleId": "b3000000-0000-4000-8000-000000000001",
                        "tariffVersion": 1,
                        "salePrice": "120.00"
                      }
                    ]
                  }
                }
                """);
        ((ObjectNode) event.path("payload")).remove("deliverySequence");
        assertFalse(schema("GoodsPosted").validate(event).isEmpty());
    }

    /** После замены eventId некорректной строкой схема отклоняет событие по формату UUID. */
    @Test
    void rejectsMalformedEventIdentifier() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        event.put("eventId", "not-a-uuid");
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Подставляет переданное неверное время; схема отклоняет невозможную дату или значение без завершающего Z. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30T10:00:00Z", "2026-10-03T10:00:00", "2026-10-03T10:00:00+03:00"})
    void rejectsInvalidOrNonUtcTimestamp(String timestamp) throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        event.put("occurredAt", timestamp);
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** После добавления неизвестного поля схема отклоняет оболочку события поставки. */
    @Test
    void rejectsUnknownEnvelopeField() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "eventType": "DeliveryReceived",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-03T10:00:00Z",
                  "storeId": "S-1",
                  "payload": {
                    "deliveryId": "D-1",
                    "items": [
                      {
                        "lineId": "L-1",
                        "productId": "P-1",
                        "productType": "NON_FOOD",
                        "shortName": "Мыло",
                        "description": "Учебный товар",
                        "quantity": 10,
                        "purchasePrice": "100.00",
                        "currency": "RUB"
                      }
                    ]
                  }
                }
                """);
        event.put("stockItemId", "supplier-must-not-create-store-inventory");
        assertFalse(schema("DeliveryReceived").validate(event).isEmpty());
    }

    /** Разбирает переданный повреждённый JSON; ожидает исключение для неверного синтаксиса, повторного ключа или лишнего документа. */
    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"schemaVersion\":1,\"schemaVersion\":2}", "{} {}"})
    void rejectsMalformedOrAmbiguousJson(String input) {
        assertThrows(JsonProcessingException.class, () -> JSON.readTree(input));
    }
}
