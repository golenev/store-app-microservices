package com.shop.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import static com.shop.contracts.ContractSupport.JSON;
import static com.shop.contracts.ContractSupport.read;
import static com.shop.contracts.ContractSupport.schema;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет явно заданные JSON-примеры и согласованность OpenAPI со схемой; HTTP-запросы не выполняет. */
class HttpContractTest {
    /** Проверяет явно заданный JSON-пример delivery-received по определению DeliveryReceived; ошибок формата нет. */
    @Test
    void acceptsDeliveryReceivedExample() throws Exception {
        JsonNode example = JSON.readTree("""
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
        assertEquals(Set.of(), schema("DeliveryReceived").validate(example));
    }

    /** Проверяет явно заданный JSON-пример goods-posted по определению GoodsPosted; ошибок формата нет. */
    @Test
    void acceptsGoodsPostedExample() throws Exception {
        JsonNode example = JSON.readTree("""
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
        assertEquals(Set.of(), schema("GoodsPosted").validate(example));
    }

    /** Проверяет явно заданный JSON-пример order-submitted по определению OrderSubmitted; ошибок формата нет. */
    @Test
    void acceptsOrderSubmittedExample() throws Exception {
        JsonNode example = JSON.readTree("""
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
        assertEquals(Set.of(), schema("OrderSubmitted").validate(example));
    }

    /** Проверяет явно заданный JSON-пример quote-request по определению QuoteRequest; ошибок формата нет. */
    @Test
    void acceptsQuoteRequestExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "productType": "NON_FOOD",
                  "purchasePrice": "100.00",
                  "currency": "RUB",
                  "cityId": "MOSCOW"
                }
                """);
        assertEquals(Set.of(), schema("QuoteRequest").validate(example));
    }

    /** Проверяет явно заданный JSON-пример quote-response по определению QuoteResponse; ошибок формата нет. */
    @Test
    void acceptsQuoteResponseExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "markupRate": "0.20",
                  "tariffRuleId": "b3000000-0000-4000-8000-000000000001",
                  "tariffVersion": 1
                }
                """);
        assertEquals(Set.of(), schema("QuoteResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример tariff-rule-request по определению TariffRuleRequest; ошибок формата нет. */
    @Test
    void acceptsTariffRuleRequestExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "productType": "NON_FOOD",
                  "cityId": "MOSCOW",
                  "currency": "RUB",
                  "lowerBound": "0.00",
                  "upperBound": "500.00",
                  "markupRate": "0.20"
                }
                """);
        assertEquals(Set.of(), schema("TariffRuleRequest").validate(example));
    }

    /** Проверяет явно заданный JSON-пример tariff-rule по определению TariffRule; ошибок формата нет. */
    @Test
    void acceptsTariffRuleExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "tariffRuleId": "b3000000-0000-4000-8000-000000000001",
                  "version": 1,
                  "productType": "NON_FOOD",
                  "cityId": "MOSCOW",
                  "currency": "RUB",
                  "lowerBound": "0.00",
                  "upperBound": "500.00",
                  "markupRate": "0.20"
                }
                """);
        assertEquals(Set.of(), schema("TariffRule").validate(example));
    }

    /** Проверяет явно заданный JSON-пример tariff-rules по определению TariffRulesResponse; ошибок формата нет. */
    @Test
    void acceptsTariffRulesExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "items": [
                    {
                      "tariffRuleId": "b3000000-0000-4000-8000-000000000001",
                      "version": 1,
                      "productType": "NON_FOOD",
                      "cityId": "MOSCOW",
                      "currency": "RUB",
                      "lowerBound": "0.00",
                      "upperBound": "500.00",
                      "markupRate": "0.20"
                    }
                  ]
                }
                """);
        assertEquals(Set.of(), schema("TariffRulesResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример cache-reset по определению CacheResetResponse; ошибок формата нет. */
    @Test
    void acceptsCacheResetExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "cache": "tariff-quotes",
                  "resetAt": "2026-10-03T21:00:00Z"
                }
                """);
        assertEquals(Set.of(), schema("CacheResetResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример delivery-waiting по определению DeliveryResponse; ошибок формата нет. */
    @Test
    void acceptsDeliveryWaitingExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "WAITING_PRICING",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "attemptCount": 1,
                  "nextAttemptAt": "2026-10-03T10:00:06Z",
                  "lastError": {
                    "code": "DEPENDENCY_UNAVAILABLE",
                    "message": "TARIFFS недоступен"
                  },
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
                """);
        assertEquals(Set.of(), schema("DeliveryResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример delivery-posted по определению DeliveryResponse; ошибок формата нет. */
    @Test
    void acceptsDeliveryPostedExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "POSTED",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "postedAt": "2026-10-03T10:00:02Z",
                  "attemptCount": 1,
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
                """);
        assertEquals(Set.of(), schema("DeliveryResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример delivery-rejected по определению DeliveryResponse; ошибок формата нет. */
    @Test
    void acceptsDeliveryRejectedExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "REJECTED",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "attemptCount": 0,
                  "lastError": {
                    "code": "VALIDATION_ERROR",
                    "message": "Позиции с повторным productId"
                  },
                  "items": [],
                  "rejectedPayload": {
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
                      },
                      {
                        "lineId": "L-2",
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
        assertEquals(Set.of(), schema("DeliveryResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример delivery-published по определению DeliveryPublishResponse; ошибок формата нет. */
    @Test
    void acceptsDeliveryPublishedExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "eventId": "b2000000-0000-4000-8000-000000000001",
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "publicationStatus": "PUBLISHED",
                  "publishedAt": "2026-10-03T10:00:00Z"
                }
                """);
        assertEquals(Set.of(), schema("DeliveryPublishResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример catalog по определению CatalogResponse; ошибок формата нет. */
    @Test
    void acceptsCatalogExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "items": [
                    {
                      "stockItemId": "b4000000-0000-4000-8000-000000000001",
                      "productId": "P-1",
                      "productType": "NON_FOOD",
                      "shortName": "Мыло",
                      "description": "Учебный товар",
                      "unitPrice": "120.00",
                      "currency": "RUB",
                      "availableQuantity": 10
                    }
                  ]
                }
                """);
        assertEquals(Set.of(), schema("CatalogResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример cart-open по определению CartResponse; ошибок формата нет. */
    @Test
    void acceptsCartOpenExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "version": 1,
                  "state": "OPEN",
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
                """);
        assertEquals(Set.of(), schema("CartResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример cart-empty по определению CartResponse; ошибок формата нет. */
    @Test
    void acceptsCartEmptyExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "version": 0,
                  "state": "OPEN",
                  "items": [],
                  "totalAmount": "0.00",
                  "currency": "RUB"
                }
                """);
        assertEquals(Set.of(), schema("CartResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример cart-submitted по определению CartResponse; ошибок формата нет. */
    @Test
    void acceptsCartSubmittedExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "version": 2,
                  "state": "SUBMITTED",
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
                  "currency": "RUB",
                  "submissionId": "b6000000-0000-4000-8000-000000000001"
                }
                """);
        assertEquals(Set.of(), schema("CartResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример put-cart-item по определению PutCartItemRequest; ошибок формата нет. */
    @Test
    void acceptsPutCartItemExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "quantity": 3,
                  "expectedCartVersion": 0
                }
                """);
        assertEquals(Set.of(), schema("PutCartItemRequest").validate(example));
    }

    /** Проверяет явно заданный JSON-пример submit-request по определению SubmitRequest; ошибок формата нет. */
    @Test
    void acceptsSubmitRequestExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "expectedCartVersion": 1
                }
                """);
        assertEquals(Set.of(), schema("SubmitRequest").validate(example));
    }

    /** Проверяет явно заданный JSON-пример submission-pending по определению SubmissionResponse; ошибок формата нет. */
    @Test
    void acceptsSubmissionPendingExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "submissionId": "b6000000-0000-4000-8000-000000000001",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "eventId": "b2000000-0000-4000-8000-000000000003",
                  "publicationStatus": "PENDING",
                  "acceptedAt": "2026-10-03T10:00:03Z"
                }
                """);
        assertEquals(Set.of(), schema("SubmissionResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример submission-published по определению SubmissionResponse; ошибок формата нет. */
    @Test
    void acceptsSubmissionPublishedExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "storeId": "S-1",
                  "submissionId": "b6000000-0000-4000-8000-000000000001",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "eventId": "b2000000-0000-4000-8000-000000000003",
                  "publicationStatus": "PUBLISHED",
                  "acceptedAt": "2026-10-03T10:00:03Z",
                  "publishedAt": "2026-10-03T10:00:04Z"
                }
                """);
        assertEquals(Set.of(), schema("SubmissionResponse").validate(example));
    }

    /** Проверяет явно заданный JSON-пример error по определению ErrorResponse; ошибок формата нет. */
    @Test
    void acceptsErrorExample() throws Exception {
        JsonNode example = JSON.readTree("""
                {
                  "timestamp": "2026-10-03T10:00:04Z",
                  "status": 409,
                  "code": "INSUFFICIENT_STOCK",
                  "message": "Недостаточно остатка товара",
                  "path": "/stores/S-1/carts/b5000000-0000-4000-8000-000000000001/submit",
                  "details": [
                    {
                      "field": "items[0].quantity",
                      "message": "Доступно 0, запрошено 3"
                    }
                  ]
                }
                """);
        assertEquals(Set.of(), schema("ErrorResponse").validate(example));
    }

    /** При нулевом остатке пример каталога проходит проверку схемы; ноль разрешён в каталоге. */
    @Test
    void allowsZeroStockInCatalog() throws Exception {
        ObjectNode catalog = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "items": [
                    {
                      "stockItemId": "b4000000-0000-4000-8000-000000000001",
                      "productId": "P-1",
                      "productType": "NON_FOOD",
                      "shortName": "Мыло",
                      "description": "Учебный товар",
                      "unitPrice": "120.00",
                      "currency": "RUB",
                      "availableQuantity": 10
                    }
                  ]
                }
                """);
        ((ObjectNode) catalog.at("/items/0")).put("availableQuantity", 0);
        assertTrue(schema("CatalogResponse").validate(catalog).isEmpty());
    }

    /** После замены остатка на отрицательный схема отклоняет пример каталога. */
    @Test
    void rejectsNegativeStockInCatalog() throws Exception {
        ObjectNode catalog = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "items": [
                    {
                      "stockItemId": "b4000000-0000-4000-8000-000000000001",
                      "productId": "P-1",
                      "productType": "NON_FOOD",
                      "shortName": "Мыло",
                      "description": "Учебный товар",
                      "unitPrice": "120.00",
                      "currency": "RUB",
                      "availableQuantity": 10
                    }
                  ]
                }
                """);
        ((ObjectNode) catalog.at("/items/0")).put("availableQuantity", -1);
        assertFalse(schema("CatalogResponse").validate(catalog).isEmpty());
    }

    /** После добавления клиентской суммы схема отклоняет запрос submit с запрещённым полем. */
    @Test
    void rejectsClientSuppliedSubmitTotal() throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {
                  "expectedCartVersion": 1
                }
                """);
        request.put("totalAmount", "0.01");
        assertFalse(schema("SubmitRequest").validate(request).isEmpty());
    }

    /** После удаления expectedCartVersion схема отклоняет запрос submit без обязательной версии. */
    @Test
    void rejectsSubmitWithoutExpectedVersion() throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {
                  "expectedCartVersion": 1
                }
                """);
        request.remove("expectedCartVersion");
        assertFalse(schema("SubmitRequest").validate(request).isEmpty());
    }

    /** После удаления publishedAt схема отклоняет ответ о заявке в состоянии PUBLISHED. */
    @Test
    void publishedSubmissionRequiresTimestamp() throws Exception {
        ObjectNode submission = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "submissionId": "b6000000-0000-4000-8000-000000000001",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "eventId": "b2000000-0000-4000-8000-000000000003",
                  "publicationStatus": "PUBLISHED",
                  "acceptedAt": "2026-10-03T10:00:03Z",
                  "publishedAt": "2026-10-03T10:00:04Z"
                }
                """);
        submission.remove("publishedAt");
        assertFalse(schema("SubmissionResponse").validate(submission).isEmpty());
    }

    /** После добавления publishedAt схема отклоняет ответ о заявке в состоянии PENDING. */
    @Test
    void pendingSubmissionCannotClaimPublishedTimestamp() throws Exception {
        ObjectNode submission = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "submissionId": "b6000000-0000-4000-8000-000000000001",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "eventId": "b2000000-0000-4000-8000-000000000003",
                  "publicationStatus": "PENDING",
                  "acceptedAt": "2026-10-03T10:00:03Z"
                }
                """);
        submission.put("publishedAt", "2026-10-03T10:00:04Z");
        assertFalse(schema("SubmissionResponse").validate(submission).isEmpty());
    }

    /** После удаления salePrice схема отклоняет ответ о поставке в состоянии POSTED. */
    @Test
    void postedDeliveryRequiresCalculatedPrice() throws Exception {
        ObjectNode delivery = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "POSTED",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "postedAt": "2026-10-03T10:00:02Z",
                  "attemptCount": 1,
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
                """);
        ((ObjectNode) delivery.at("/items/0")).remove("salePrice");
        assertFalse(schema("DeliveryResponse").validate(delivery).isEmpty());
    }

    /** После удаления submissionId схема отклоняет ответ о закрытой корзине. */
    @Test
    void submittedCartRequiresSubmissionIdentifier() throws Exception {
        ObjectNode cart = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "cartId": "b5000000-0000-4000-8000-000000000001",
                  "version": 2,
                  "state": "SUBMITTED",
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
                  "currency": "RUB",
                  "submissionId": "b6000000-0000-4000-8000-000000000001"
                }
                """);
        cart.remove("submissionId");
        assertFalse(schema("CartResponse").validate(cart).isEmpty());
    }

    /** При отрицательном количестве исходных данных ответ REJECTED допустим, но сам payload поставки не проходит схему. */
    @Test
    void rejectedDeliveryCanExposeInvalidOriginalQuantity() throws Exception {
        ObjectNode delivery = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "REJECTED",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "attemptCount": 0,
                  "lastError": {
                    "code": "VALIDATION_ERROR",
                    "message": "Позиции с повторным productId"
                  },
                  "items": [],
                  "rejectedPayload": {
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
                      },
                      {
                        "lineId": "L-2",
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
        ((ObjectNode) delivery.at("/rejectedPayload/items/0")).put("quantity", -1);
        assertTrue(schema("DeliveryResponse").validate(delivery).isEmpty());
        assertFalse(schema("DeliveryPayload").validate(delivery.path("rejectedPayload")).isEmpty());
    }

    /** После удаления rejectedPayload схема отклоняет ответ REJECTED без исходных данных для диагностики. */
    @Test
    void rejectedDeliveryRequiresOriginalPayload() throws Exception {
        ObjectNode delivery = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "REJECTED",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "attemptCount": 0,
                  "lastError": {
                    "code": "VALIDATION_ERROR",
                    "message": "Позиции с повторным productId"
                  },
                  "items": [],
                  "rejectedPayload": {
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
                      },
                      {
                        "lineId": "L-2",
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
        delivery.remove("rejectedPayload");
        assertFalse(schema("DeliveryResponse").validate(delivery).isEmpty());
    }

    /** После удаления всех позиций схема отклоняет ответ о поставке в состоянии WAITING_PRICING. */
    @Test
    void waitingDeliveryCannotHaveEmptyItems() throws Exception {
        ObjectNode delivery = (ObjectNode) JSON.readTree("""
                {
                  "storeId": "S-1",
                  "deliveryId": "D-1",
                  "deliverySequence": 1,
                  "state": "WAITING_PRICING",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "attemptCount": 1,
                  "nextAttemptAt": "2026-10-03T10:00:06Z",
                  "lastError": {
                    "code": "DEPENDENCY_UNAVAILABLE",
                    "message": "TARIFFS недоступен"
                  },
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
                """);
        delivery.putArray("items");
        assertFalse(schema("DeliveryResponse").validate(delivery).isEmpty());
    }

    /** Для сохранённого OpenAPI проверяет версию и существование каждого определения по внешней ссылке. */
    @Test
    void openApiReferencesResolveToCanonicalDefinitions() throws Exception {
        JsonNode api = read("openapi.json");
        assertEquals("3.1.0", api.path("openapi").asText());
        Set<String> references = new HashSet<>();
        collectReferences(api, references);
        assertFalse(references.isEmpty());
        for (String reference : references) {
            String prefix = "./schemas/shop-v1.schema.json#/$defs/";
            assertTrue(reference.startsWith(prefix), reference);
            assertNotNull(schema(reference.substring(prefix.length())));
        }
    }

    /** Разбирает сохранённый OpenAPI с разрешением внешних ссылок; ожидает документ без диагностических сообщений. */
    @Test
    void openApiDocumentParsesWithoutDiagnostics() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        String location = java.util.Objects.requireNonNull(
                getClass().getResource("/contracts/openapi.json")).toExternalForm();
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location, null, options);

        assertNotNull(result.getOpenAPI());
        assertTrue(result.getMessages() == null || result.getMessages().isEmpty(),
                () -> String.valueOf(result.getMessages()));
    }

    /** В сохранённом OpenAPI проверяет обязательный заголовок Idempotency-Key и успешный ответ 202 вместо 200 для submit. */
    @Test
    void submitDocumentsRequiredKeyAndAcceptedResponse() throws Exception {
        JsonNode operation = read("openapi.json").path("paths")
                .path("/stores/{storeId}/carts/{cartId}/submit").path("post");
        JsonNode key = StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(parameter -> parameter.path("name").asText().equals("Idempotency-Key"))
                .findFirst().orElseThrow();
        assertEquals("header", key.path("in").asText());
        assertTrue(key.path("required").asBoolean());
        assertTrue(operation.path("responses").has("202"));
        assertFalse(operation.path("responses").has("200"));
    }

    /** Обходит переданный JSON и добавляет все ссылки $ref в переданный набор; исходный документ не изменяет. */
    private static void collectReferences(JsonNode node, Set<String> references) {
        if (node.isObject() && node.has("$ref")) {
            references.add(node.path("$ref").asText());
        }
        node.forEach(child -> collectReferences(child, references));
    }
}
