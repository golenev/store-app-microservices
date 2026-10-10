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

/**
 * Проверяет JSON-события поставок и подготавливает их содержимое для приёмки. Вычисляет контрольную сумму
 * содержимого, чтобы сравнивать повторные сообщения.
 */
@Component
public class DeliveryCodec {
    public static final String ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}";
    public static final String MONEY = "(0|[1-9][0-9]{0,25})\\.[0-9]{2}";
    public static final String RATE = "(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}";
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final ObjectMapper mapper;

    /**
     * Создаёт отдельную настройку чтения JSON: повторные поля, неизвестные поля при восстановлении Java-модели
     * и данные после первого документа считаются ошибкой.
     *
     * @param mapper настройки преобразования Java-объектов и JSON
     */
    public DeliveryCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Читает событие {@code DeliveryReceived} и возвращает идентификаторы, исходное содержимое, проверенные
     * строки и контрольную сумму. Если строки поставки неверны, возвращает причину отклонения и пустой список
     * строк для сохранения состояния {@code REJECTED}. Если нельзя достоверно определить событие, магазин или
     * поставку, выдаёт {@code VALIDATION_ERROR} для диагностики сообщения Kafka.
     *
     * @param raw JSON-событие {@code DeliveryReceived} из Kafka
     * @return идентифицированная поставка с проверенными строками или причиной их отклонения
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
     * Проверяет, что JSON-объект содержит только разрешённые имена полей. Обязательные поля проверяются при
     * чтении; неверный тип объекта или лишнее поле вызывает {@code VALIDATION_ERROR}.
     *
     * @param node JSON-значение, которое нужно проверить или преобразовать
     * @param allowed разрешённые имена полей JSON-объекта
     */
    private void fields(JsonNode node, Set<String> allowed) {
        require(node.isObject(), "Expected JSON object");
        node.fieldNames().forEachRemaining(field -> require(allowed.contains(field), "Unknown field"));
    }

    /**
     * Читает обязательное строковое поле JSON. Отсутствие поля, {@code null}, число или логическое значение
     * отклоняет с {@code VALIDATION_ERROR} без преобразования в строку.
     *
     * @param node JSON-значение, которое нужно проверить или преобразовать
     * @param field имя читаемого поля
     * @return строковое значение обязательного поля
     */
    private String text(JsonNode node, String field) {
        require(node.path(field).isTextual(), "Invalid or missing " + field);
        return node.get(field).textValue();
    }

    /**
     * Проверяет идентификатор длиной от 1 до 64 символов. Первый символ должен быть латинской буквой или
     * цифрой; далее разрешены также {@code . _ : -}. Возвращает исходную строку, а нарушение формата отклоняет
     * с {@code VALIDATION_ERROR}.
     *
     * @param value проверяемый идентификатор магазина, поставки, продукта или строки
     * @return допустимый идентификатор без изменений
     */
    public String identifier(String value) {
        require(value != null && value.matches(ID), "Invalid identifier");
        return value;
    }

    /**
     * Преобразует строку в UUID только в полном формате из пяти групп: 8, 4, 4, 4 и 12 шестнадцатеричных цифр.
     * Сокращённую или неверную запись отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value строковая запись UUID
     * @return UUID, прочитанный из полной строковой записи
     */
    public UUID uuid(String value) {
        require(value != null && value.matches(UUID_PATTERN), "Invalid UUID");
        return UUID.fromString(value);
    }

    /**
     * Читает ровно один JSON-документ. Пустое тело, повторные поля, повреждённый JSON или данные после
     * документа вызывают {@code VALIDATION_ERROR}.
     *
     * @param raw исходный текст JSON-документа
     * @return прочитанное или упорядоченное JSON-значение
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
     * Преобразует объект в JSON для хранения или отправки; даты записывает с настройками приложения. При
     * невозможности преобразования выбрасывает {@code IllegalStateException} и прерывает операцию.
     *
     * @param value Java-объект для преобразования в JSON
     * @return JSON-запись переданного объекта
     */
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize delivery data", failure); }
    }

    /**
     * Восстанавливает Java-объект указанного типа из сохранённого JSON. Повреждённые данные вызывают {@code
     * IllegalStateException}; вымышленный результат не создаётся.
     *
     * @param <T> тип модели, которую нужно прочитать из JSON
     * @param raw JSON ранее сохранённой модели
     * @param type Java-класс модели, в которую нужно прочитать JSON
     * @return объект указанного типа, прочитанный из JSON
     */
    public <T> T restore(String raw, Class<T> type) {
        try { return mapper.readValue(raw, type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid stored delivery data", failure); }
    }

    /**
     * Вычисляет SHA-256 содержимого поставки: упорядочивает строки по {@code lineId} и поля объектов по имени.
     * Идентификатор и время транспортного события в расчёт не входят, поэтому повтор с тем же содержимым можно
     * распознать.
     *
     * @param payload исходное содержимое поставки с идентификатором и строками товаров
     * @return контрольная сумма содержимого поставки в шестнадцатеричной записи
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
     * Создаёт копию JSON с полями объектов в алфавитном порядке на всех уровнях. Порядок элементов массивов и
     * значения строк сохраняет.
     *
     * @param node JSON-значение, которое нужно проверить или преобразовать
     * @return прочитанное или упорядоченное JSON-значение
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
     * Создаёт ошибку HTTP 400 с кодом {@code VALIDATION_ERROR} и переданным пояснением. Сам метод ошибку не
     * выбрасывает.
     *
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @return исключение поставки с подготовленными статусом, кодом и пояснением
     */
    private DeliveryException invalid(String message) { return new DeliveryException(400, "VALIDATION_ERROR", message); }

    /**
     * Продолжает проверку, если условие выполнено. Иначе выбрасывает ошибку HTTP 400 с кодом {@code
     * VALIDATION_ERROR} и переданным пояснением.
     *
     * @param condition условие, которое должно выполняться для допустимых данных
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    private void require(boolean condition, String message) { if (!condition) throw invalid(message); }
}
