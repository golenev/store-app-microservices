package com.shop.warehouse.codec;

import com.shop.warehouse.dto.Failure;
import com.shop.warehouse.exception.DeliveryException;
import com.shop.warehouse.messaging.dto.InputLine;
import com.shop.warehouse.model.Accepted;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Проверяет конверт поставки и нормализует бизнес-содержимое для Kafka-приёмки и учебного поставщика. */
@Component
public class DeliveryCodec {
    public static final String ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}";
    public static final String MONEY = "(0|[1-9][0-9]{0,25})\\.[0-9]{2}";
    public static final String RATE = "(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}";
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final ObjectMapper mapper;

    /**
     * Копирует ObjectMapper и включает проверку повторных ключей, неизвестных полей и лишнего содержимого.
     *
     * @param mapper ObjectMapper приложения для согласованного JSON
     */
    public DeliveryCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Разбирает raw и возвращает идентифицируемую поставку с отпечатком. Ошибка payload становится REJECTED;
     * ошибка конверта вызывает VALIDATION_ERROR для диагностики Kafka.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public Accepted decode(String raw) {
        JsonNode root = read(raw);
        fields(root, Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "storeId", "payload"));
        UUID eventId = uuid(text(root, "eventId"));
        require("DeliveryReceived".equals(text(root, "eventType")), "Unsupported eventType");
        require(root.path("schemaVersion").isIntegralNumber() && root.path("schemaVersion").canConvertToInt()
                && root.path("schemaVersion").intValue() == 1, "Unsupported schemaVersion");
        String occurredAt = text(root, "occurredAt");
        require(occurredAt.endsWith("Z"), "occurredAt must be UTC");
        try { Instant.parse(occurredAt); } catch (RuntimeException failure) { throw invalid("Invalid occurredAt"); }
        String storeId = identifier(text(root, "storeId"));
        JsonNode payload = root.path("payload");
        require(payload.isObject(), "payload must be an object");
        String deliveryId = identifier(text(payload, "deliveryId"));
        List<InputLine> lines = new ArrayList<>();
        Failure rejection = null;
        try {
            fields(payload, Set.of("deliveryId", "items"));
            JsonNode items = payload.path("items");
            require(items.isArray() && items.size() > 0 && items.size() <= 1000, "items must contain 1 to 1000 lines");
            Set<String> lineIds = new HashSet<>(), products = new HashSet<>();
            for (JsonNode item : items) {
                fields(item, Set.of("lineId", "productId", "productType", "shortName", "description", "quantity", "purchasePrice", "currency"));
                String lineId = identifier(text(item, "lineId")), productId = identifier(text(item, "productId"));
                require(lineIds.add(lineId) && products.add(productId), "Duplicate lineId or productId");
                String type = text(item, "productType"), name = text(item, "shortName"), description = text(item, "description");
                require(Set.of("FOOD", "NON_FOOD").contains(type), "Invalid productType");
                require(!name.isBlank() && name.codePointCount(0, name.length()) <= 255, "Invalid shortName");
                require(description.codePointCount(0, description.length()) <= 2000, "Invalid description");
                JsonNode quantity = item.path("quantity");
                require(quantity.isIntegralNumber() && quantity.canConvertToInt() && quantity.intValue() > 0, "Invalid quantity");
                String price = text(item, "purchasePrice"), currency = text(item, "currency");
                require(price.matches(MONEY) && new BigDecimal(price).signum() > 0, "Invalid purchasePrice");
                require("RUB".equals(currency), "Invalid currency");
                lines.add(new InputLine(lineId, productId, type, name, description, quantity.intValue(), price, currency));
            }
        } catch (DeliveryException failure) {
            lines.clear();
            rejection = new Failure(failure.code(), failure.getMessage());
        }
        lines.sort(Comparator.comparing(InputLine::lineId));
        return new Accepted(eventId, storeId, deliveryId, payload, List.copyOf(lines), fingerprint(payload), rejection);
    }

    /**
     * Проверяет объект node на допустимые имена allowed; обязательные поля дополнительно проверяются при
     * чтении.
     *
     * @param node проверяемый узел JSON
     * @param allowed разрешённые имена полей объекта
     */
    private void fields(JsonNode node, Set<String> allowed) {
        require(node.isObject(), "Expected JSON object");
        node.fieldNames().forEachRemaining(field -> require(allowed.contains(field), "Unknown field"));
    }

    /**
     * Возвращает обязательную строку field из node, отклоняя числа, boolean, null и отсутствующее поле.
     *
     * @param node проверяемый узел JSON
     * @param field имя читаемого поля
     */
    private String text(JsonNode node, String field) {
        require(node.path(field).isTextual(), "Invalid or missing " + field);
        return node.get(field).textValue();
    }

    /**
     * Возвращает исходный регистрозависимый идентификатор value для тела и URL; неверный формат вызывает
     * VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public String identifier(String value) {
        require(value != null && value.matches(ID), "Invalid identifier");
        return value;
    }

    /**
     * Разбирает только полный UUID value; сокращённое представление отклоняет до permissive-разбора Java.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public UUID uuid(String value) {
        require(value != null && value.matches(UUID_PATTERN), "Invalid UUID");
        return UUID.fromString(value);
    }

    /**
     * Читает один JSON-документ raw; повреждённый или пустой ввод отклоняет без вымышленных идентификаторов
     * поставки.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public JsonNode read(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node == null || node.isMissingNode()) throw invalid("Empty JSON document");
            return node;
        }
        catch (Exception failure) { throw invalid("Malformed JSON"); }
    }

    /**
     * Сериализует value для событий и диагностики в согласованном UTC-представлении; ошибка прерывает текущую
     * операцию.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize delivery data", failure); }
    }

    /**
     * Восстанавливает сохранённый raw в type; повреждённые данные вызывают IllegalStateException вместо подмены
     * результата.
     *
     * @param <T> тип восстанавливаемых данных
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @param type тип восстанавливаемого сохранённого объекта
     */
    public <T> T restore(String raw, Class<T> type) {
        try { return mapper.readValue(raw, type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid stored delivery data", failure); }
    }

    /**
     * Возвращает SHA-256 бизнес-содержимого payload с сортировкой строк и ключей; транспортные eventId и
     * occurredAt не включаются.
     *
     * @param payload сериализованное содержимое события или проверенный объект согласно типу
     */
    private String fingerprint(JsonNode payload) {
        ObjectNode normalized = ((ObjectNode) payload).deepCopy();
        if (normalized.path("items").isArray()) {
            List<JsonNode> items = new ArrayList<>();
            normalized.get("items").forEach(items::add);
            items.sort(Comparator.comparing(item -> item.path("lineId").asText()));
            normalized.set("items", mapper.valueToTree(items));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(canonical(normalized)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /**
     * Рекурсивно сортирует ключи объекта node, сохраняя порядок массивов и значимые строки.
     *
     * @param node проверяемый узел JSON
     */
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }

    /**
     * Создаёт безопасную ошибку VALIDATION_ERROR с пояснением message для HTTP и диагностики.
     *
     * @param message безопасное пояснение без секретов
     */
    private DeliveryException invalid(String message) { return new DeliveryException(400, "VALIDATION_ERROR", message); }

    /**
     * Проверяет condition; нарушение вызывает VALIDATION_ERROR с безопасным message.
     *
     * @param condition проверяемый инвариант протокола
     * @param message безопасное пояснение без секретов
     */
    private void require(boolean condition, String message) { if (!condition) throw invalid(message); }
}
