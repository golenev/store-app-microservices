package com.shop.store.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Сохраняет требование строковой даты UTC с окончанием Z. */
public final class ShopInstantDeserializer extends StdDeserializer<Instant> {
    /** Создаёт обработчик момента времени без общего изменяемого состояния. */
    ShopInstantDeserializer() { super(Instant.class); }

    /**
     * Читает ISO-дату с окончанием Z; числовые даты и другие смещения отклоняет.
     * @param parser текущий токен JSON
     * @param context контекст чтения
     * @return момент времени в UTC
     * @throws IOException при неверном типе или дате
     */
    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING) || !parser.getText().endsWith("Z")) {
            return context.reportInputMismatch(Instant.class, "Timestamp must be a UTC string ending in Z");
        }
        try {
            return Instant.parse(parser.getText());
        } catch (DateTimeParseException failure) {
            return context.reportInputMismatch(Instant.class, "Invalid timestamp");
        }
    }
}
