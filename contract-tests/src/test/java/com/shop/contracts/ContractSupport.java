package com.shop.contracts;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;

/** Local test utilities; services must not depend on this class or module. */
final class ContractSupport {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Prevents construction: helpers only read immutable contract resources. */
    private ContractSupport() {
    }

    /**
     * Reads a classpath contract as strict JSON, rejecting malformed or duplicate fields.
     * @param path path relative to contracts, independent of the working directory
     * @return the parsed JSON without coercing decimal strings into numbers
     * @throws IOException if a resource is absent or is not valid JSON
     */
    static JsonNode read(String path) throws IOException {
        try (InputStream input = ContractSupport.class.getResourceAsStream("/contracts/" + path)) {
            if (input == null) {
                throw new IOException("Missing contract resource: " + path);
            }
            return JSON.readTree(input);
        }
    }

    /**
     * Loads a named canonical v1 definition with UUID/date-time format assertions enabled.
     * The local $defs remain attached, so validation never fetches application schemas.
     * @param definition wire definition to validate, such as GoodsPosted
     * @return a validator for this definition, rather than the root event union
     * @throws IOException if the schema resource cannot be read
     */
    static JsonSchema schema(String definition) throws IOException {
        ObjectNode root = (ObjectNode) read("schemas/shop-v1.schema.json");
        if (!root.path("$defs").has(definition)) {
            throw new IOException("Unknown contract definition: " + definition);
        }
        root.remove("oneOf");
        root.put("$ref", "#/$defs/" + definition);
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config);
    }
}
