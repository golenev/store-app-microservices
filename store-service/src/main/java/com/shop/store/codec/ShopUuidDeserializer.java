package com.shop.store.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.UUID;

/** Сохраняет полный строковый формат UUID входного контракта магазина. */
public final class ShopUuidDeserializer extends StdDeserializer<UUID> {
    /** Создаёт обработчик для UUID; состояние между документами не хранится. */
    ShopUuidDeserializer() { super(UUID.class); }

    /**
     * Принимает только строку UUID из групп 8-4-4-4-12, включая оба регистра букв.
     * @param parser текущий токен JSON
     * @param context контекст чтения
     * @return UUID без изменения значения
     * @throws IOException при неверном типе или формате
     */
    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)
                || !parser.getText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return context.reportInputMismatch(UUID.class, "Expected full UUID string");
        }
        return UUID.fromString(parser.getText());
    }
}
