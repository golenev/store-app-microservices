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

/** Загружает только спецификации для тестов; работающие сервисы не зависят от этого класса. */
final class ContractSupport {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Запрещает создание экземпляров: класс предоставляет только статические средства проверки. */
    private ContractSupport() {
    }

    /**
     * Читает спецификацию из ресурсов как строгий JSON, отклоняя повреждённый синтаксис и повторные ключи.
     * @param path путь относительно contracts в ресурсах теста
     * @return JSON без преобразования денежных строк в числа
     * @throws IOException если файл отсутствует или содержит некорректный JSON
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
     * Создаёт валидатор выбранного определения v1 с проверкой форматов UUID и времени.
     * Сохраняет локальные определения $defs и не обращается к сервисам или внешним схемам.
     * @param definition имя определения, например GoodsPosted
     * @return валидатор указанного определения вместо объединения всех событий
     * @throws IOException если схема недоступна, повреждена или определение отсутствует
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
