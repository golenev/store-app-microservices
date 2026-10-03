package com.shop.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.shop.contracts.ContractSupport.read;
import static com.shop.contracts.ContractSupport.schema;
import static org.junit.jupiter.api.Assertions.*;

/** Validates HTTP wire examples and the connections between OpenAPI and canonical schemas. */
class HttpContractTest {
    /** Every registered event/HTTP example must satisfy its explicitly named wire definition. */
    @TestFactory
    Stream<DynamicTest> publishedExamplesMatchCanonicalDefinitions() throws Exception {
        JsonNode manifest = read("examples/manifest.json");
        return StreamSupport.stream(manifest.spliterator(), false).map(entry ->
                DynamicTest.dynamicTest(entry.path("path").asText(), () -> {
                    JsonNode example = read(entry.path("path").asText());
                    assertEquals(Set.of(), schema(entry.path("definition").asText()).validate(example));
                }));
    }

    /** Zero stock is a valid catalogue result even though deliveries require positive quantity. */
    @Test
    void allowsZeroStockInCatalog() throws Exception {
        ObjectNode catalog = (ObjectNode) read("examples/http/catalog.json");
        ((ObjectNode) catalog.at("/items/0")).put("availableQuantity", 0);
        assertTrue(schema("CatalogResponse").validate(catalog).isEmpty());
    }

    /** Negative stock is structurally invalid; database/concurrency protection belongs to STORE. */
    @Test
    void rejectsNegativeStockInCatalog() throws Exception {
        ObjectNode catalog = (ObjectNode) read("examples/http/catalog.json");
        ((ObjectNode) catalog.at("/items/0")).put("availableQuantity", -1);
        assertFalse(schema("CatalogResponse").validate(catalog).isEmpty());
    }

    /** Submit accepts only expectedCartVersion, so client totals cannot become authoritative. */
    @Test
    void rejectsClientSuppliedSubmitTotal() throws Exception {
        ObjectNode request = (ObjectNode) read("examples/http/submit-request.json");
        request.put("totalAmount", "0.01");
        assertFalse(schema("SubmitRequest").validate(request).isEmpty());
    }

    /** Versions are mandatory: a missing version cannot bypass concurrent-cart detection. */
    @Test
    void rejectsSubmitWithoutExpectedVersion() throws Exception {
        ObjectNode request = (ObjectNode) read("examples/http/submit-request.json");
        request.remove("expectedCartVersion");
        assertFalse(schema("SubmitRequest").validate(request).isEmpty());
    }

    /** Published operations must expose their acknowledgement timestamp. */
    @Test
    void publishedSubmissionRequiresTimestamp() throws Exception {
        ObjectNode submission = (ObjectNode) read("examples/http/submission-published.json");
        submission.remove("publishedAt");
        assertFalse(schema("SubmissionResponse").validate(submission).isEmpty());
    }

    /** PENDING never claims a saved publication timestamp even if Kafka accepted an unrecorded send. */
    @Test
    void pendingSubmissionCannotClaimPublishedTimestamp() throws Exception {
        ObjectNode submission = (ObjectNode) read("examples/http/submission-pending.json");
        submission.put("publishedAt", "2026-10-03T10:00:04Z");
        assertFalse(schema("SubmissionResponse").validate(submission).isEmpty());
    }

    /** A POSTED delivery requires its completion timestamp and every calculated item price. */
    @Test
    void postedDeliveryRequiresCalculatedPrice() throws Exception {
        ObjectNode delivery = (ObjectNode) read("examples/http/delivery-posted.json");
        ((ObjectNode) delivery.at("/items/0")).remove("salePrice");
        assertFalse(schema("DeliveryResponse").validate(delivery).isEmpty());
    }

    /** A closed cart must identify the accepted operation whose snapshot it exposes. */
    @Test
    void submittedCartRequiresSubmissionIdentifier() throws Exception {
        ObjectNode cart = (ObjectNode) read("examples/http/cart-submitted.json");
        cart.remove("submissionId");
        assertFalse(schema("CartResponse").validate(cart).isEmpty());
    }

    /** REJECTED exposes the invalid original payload without pretending it is a valid delivery. */
    @Test
    void rejectedDeliveryCanExposeInvalidOriginalQuantity() throws Exception {
        ObjectNode delivery = (ObjectNode) read("examples/http/delivery-rejected.json");
        ((ObjectNode) delivery.at("/rejectedPayload/items/0")).put("quantity", -1);
        assertTrue(schema("DeliveryResponse").validate(delivery).isEmpty());
        assertFalse(schema("DeliveryPayload").validate(delivery.path("rejectedPayload")).isEmpty());
    }

    /** REJECTED without diagnostic content is not a useful persisted result for clients or tests. */
    @Test
    void rejectedDeliveryRequiresOriginalPayload() throws Exception {
        ObjectNode delivery = (ObjectNode) read("examples/http/delivery-rejected.json");
        delivery.remove("rejectedPayload");
        assertFalse(schema("DeliveryResponse").validate(delivery).isEmpty());
    }

    /** WAITING_PRICING must contain validated lines; only REJECTED may have an empty item list. */
    @Test
    void waitingDeliveryCannotHaveEmptyItems() throws Exception {
        ObjectNode delivery = (ObjectNode) read("examples/http/delivery-waiting.json");
        delivery.putArray("items");
        assertFalse(schema("DeliveryResponse").validate(delivery).isEmpty());
    }

    /** All local schema references are resolvable; renamed definitions cannot silently break OpenAPI. */
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

    /** The OpenAPI parser resolves external definitions and reports invalid document structure. */
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

    /** The submit operation documents required retry identity and only a post-commit 202 outcome. */
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

    /** Recursively gathers $refs from object/array children without interpreting external documents. */
    private static void collectReferences(JsonNode node, Set<String> references) {
        if (node.isObject() && node.has("$ref")) {
            references.add(node.path("$ref").asText());
        }
        node.forEach(child -> collectReferences(child, references));
    }
}
