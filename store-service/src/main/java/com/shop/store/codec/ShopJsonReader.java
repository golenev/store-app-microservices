package com.shop.store.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.shop.store.exception.ShopException;

import java.time.Instant;
import java.util.UUID;

/** Читает входные DTO без преобразования неверных JSON-типов в допустимые значения. */
public final class ShopJsonReader {
    private final ObjectMapper mapper;

    /**
     * Копирует настройки приложения, не меняя общий mapper. Запрещает лишние, пропущенные,
     * повторные и null-поля, хвост документа и неявное преобразование скалярных типов.
     * @param source настройки JSON приложения
     */
    ShopJsonReader(ObjectMapper source) {
        mapper = source.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS,
                        DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        for (CoercionInputShape shape : new CoercionInputShape[]{CoercionInputShape.String,
                CoercionInputShape.EmptyString, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            mapper.coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail);
        }
        for (CoercionInputShape shape : new CoercionInputShape[]{CoercionInputShape.Integer,
                CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        SimpleModule protocol = new SimpleModule();
        protocol.addDeserializer(UUID.class, new ShopUuidDeserializer());
        protocol.addDeserializer(Instant.class, new ShopInstantDeserializer());
        mapper.registerModule(protocol);
    }

    /**
     * Читает один объект заданного DTO; неверный документ отклоняет с VALIDATION_ERROR.
     * @param raw исходный JSON
     * @param type класс DTO
     * @param <T> тип результата
     * @return заполненный DTO без нормализации
     */
    <T> T read(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            throw new ShopException(400, "VALIDATION_ERROR", "Empty JSON document");
        }
        try {
            T result = mapper.readValue(raw, type);
            if (result == null) {
                throw new ShopException(400, "VALIDATION_ERROR", "Expected JSON object");
            }
            return result;
        } catch (JsonProcessingException failure) {
            throw new ShopException(400, "VALIDATION_ERROR", "Malformed JSON or invalid field type");
        }
    }
}
