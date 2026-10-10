package com.shop.store.codec;

import com.shop.store.dto.Cart;
import com.shop.store.dto.PutItem;
import com.shop.store.dto.SubmitInput;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.GoodsEvent;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.messaging.dto.PostedPayload;
import com.shop.store.model.Decoded;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Строго проверяет и сериализует протокол STORE; финансовые значения не преобразуются в floating point. */
@Component
public class ShopCodec {
    public static final long MAX_VERSION=9007199254740991L;
    private static final String MONEY="(0|[1-9][0-9]{0,25})\\.[0-9]{2}";
    private final ObjectMapper mapper;
    /**
     * Копирует ObjectMapper и включает строгий разбор одного документа без неизвестных или повторных ключей.
     *
     * @param mapper ObjectMapper приложения для согласованного JSON
     */
    public ShopCodec(ObjectMapper mapper) {
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    /**
     * Разбирает raw как GoodsPosted, проверяет финансовые поля и нормализует порядок строк, наценку и время.
     * Возвращает событие с отпечатком без eventId; неверный ввод вызывает VALIDATION_ERROR.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public Decoded goods(String raw) {
        JsonNode root=read(raw);
        fields(root,Set.of("eventId","eventType","schemaVersion","occurredAt","storeId","payload"));
        UUID eventId=uuid(text(root,"eventId"));
        require(text(root,"eventType").equals("GoodsPosted"),"Unsupported eventType");
        require(integer(root.get("schemaVersion"),1,1)==1,"Unsupported schemaVersion");
        String store=identifier(text(root,"storeId"));
        Instant occurred=utc(text(root,"occurredAt"));
        JsonNode payload=root.path("payload");
        fields(payload,Set.of("deliveryId","deliverySequence","receivedAt","postedAt","items"));
        String delivery=identifier(text(payload,"deliveryId"));
        long sequence=integer(payload.get("deliverySequence"),1,MAX_VERSION);
        Instant received=utc(text(payload,"receivedAt")),posted=utc(text(payload,"postedAt"));
        require(!posted.isBefore(received) && posted.equals(occurred),"Inconsistent GoodsPosted timestamps");
        JsonNode items=payload.path("items");
        require(items.isArray() && items.size()>0 && items.size()<=1000,"items must contain 1 to 1000 lines");
        List<PostedLine> lines=new ArrayList<>();
        Set<String> lineIds=new HashSet<>(),products=new HashSet<>();
        for(JsonNode line:items) {
            fields(line,Set.of("lineId","productId","productType","shortName","description","quantity","purchasePrice","currency",
                    "markupRate","tariffRuleId","tariffVersion","salePrice"));
            String lineId=identifier(text(line,"lineId")),product=identifier(text(line,"productId"));
            require(lineIds.add(lineId) && products.add(product),"Duplicate lineId or productId");
            String type=text(line,"productType"),name=text(line,"shortName"),description=text(line,"description");
            require(Set.of("FOOD","NON_FOOD").contains(type),"Invalid productType");
            require(!name.isBlank() && name.codePointCount(0,name.length())<=255,"Invalid shortName");
            require(description.codePointCount(0,description.length())<=2000,"Invalid description");
            int quantity=(int)integer(line.get("quantity"),1,Integer.MAX_VALUE);
            String purchase=price(text(line,"purchasePrice")),sale=price(text(line,"salePrice")),rate=text(line,"markupRate");
            require(rate.matches("(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}"),"Invalid markupRate");
            BigDecimal markup=new BigDecimal(rate).setScale(6);
            String calculated=new BigDecimal(purchase).multiply(BigDecimal.ONE.add(markup)).setScale(2,RoundingMode.HALF_UP).toPlainString();
            require(calculated.equals(sale),"salePrice does not match purchasePrice and markupRate");
            String currency=text(line,"currency"); require(currency.equals("RUB"),"Invalid currency");
            lines.add(new PostedLine(lineId,product,type,name,description,quantity,purchase,currency,markup.toPlainString(),
                    uuid(text(line,"tariffRuleId")),integer(line.get("tariffVersion"),1,MAX_VERSION),sale));
        }
        lines.sort(Comparator.comparing(PostedLine::lineId));
        PostedPayload data=new PostedPayload(delivery,sequence,received,posted,List.copyOf(lines));
        GoodsEvent event=new GoodsEvent(eventId,"GoodsPosted",1,occurred,store,data);
        return new Decoded(event,fingerprint(data));
    }
    /**
     * Разбирает raw как полную замену количества с ожидаемой версией; цены и неизвестные поля отклоняет с
     * VALIDATION_ERROR.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public PutItem put(String raw) {
        JsonNode node=read(raw); fields(node,Set.of("quantity","expectedCartVersion"));
        return new PutItem((int)integer(node.get("quantity"),1,Integer.MAX_VALUE),integer(node.get("expectedCartVersion"),0,MAX_VERSION));
    }
    /**
     * Разбирает raw с единственной ожидаемой версией корзины; состав и цены определяет сервер.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public SubmitInput submit(String raw) {
        JsonNode node=read(raw); fields(node,Set.of("expectedCartVersion"));
        return new SubmitInput(integer(node.get("expectedCartVersion"),0,MAX_VERSION));
    }
    /**
     * Проверяет регистрозависимый ключ операции магазина и возвращает его без изменений; неверный формат
     * вызывает VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public String idempotencyKey(String value) {
        require(value!=null && value.matches("[A-Za-z0-9._:-]{1,128}"),"Invalid Idempotency-Key"); return value;
    }
    /**
     * Возвращает SHA-256 канонической комбинации магазина, корзины и ожидаемой версии; состав и цены не входят
     * в запрос.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param version версия правила или корзины согласно операции
     */
    public String submissionFingerprint(String store,UUID cart,long version) {
        return hash(json(List.of(store,cart.toString(),version)));
    }
    /**
     * Восстанавливает принятую корзину из сохранённого raw; повреждённый снимок вызывает
     * DEPENDENCY_UNAVAILABLE.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public Cart cartSnapshot(String raw) {
        try { return mapper.readValue(raw,Cart.class); }
        catch(Exception failure) { throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart snapshot unavailable"); }
    }
    /**
     * Проверяет строку версии без переполнения или дробного преобразования и возвращает допустимый long.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public long version(String value) {
        require(value!=null && value.matches("(0|[1-9][0-9]{0,15})"),"Invalid expectedCartVersion");
        long number=Long.parseLong(value); require(number<=MAX_VERSION,"Invalid expectedCartVersion"); return number;
    }
    /**
     * Проверяет регистрозависимый идентификатор протокола и возвращает его без исправления; неверный формат
     * вызывает VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public String identifier(String value) {
        require(value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"),"Invalid identifier"); return value;
    }
    /**
     * Проверяет полный формат UUID перед разбором; сокращённые представления отклоняет с VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public UUID uuid(String value) {
        require(value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"Invalid UUID");
        return UUID.fromString(value);
    }
    /**
     * Возвращает положительную денежную строку с двумя знаками после точки; неверный масштаб или предел
     * вызывает VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private String price(String value) {
        require(value.matches(MONEY) && new BigDecimal(value).signum()>0,"Invalid price"); return value;
    }
    /**
     * Разбирает UTC-время и возвращает Instant; отсутствие Z или неверный формат вызывает VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private Instant utc(String value) {
        require(value.endsWith("Z"),"Timestamp must be UTC");
        try { return Instant.parse(value); } catch(RuntimeException failure) { throw invalid("Invalid timestamp"); }
    }
    /**
     * Читает целое JSON-значение в пределах lower и upper, включая границы; null, дробь и переполнение
     * отклоняет.
     *
     * @param node проверяемый узел JSON
     * @param lower включённая нижняя граница целого значения
     * @param upper включённая верхняя граница целого значения
     */
    private long integer(JsonNode node,long lower,long upper) {
        require(node!=null && node.isIntegralNumber() && node.canConvertToLong(),"Invalid integer");
        long value=node.longValue(); require(value>=lower && value<=upper,"Integer out of range"); return value;
    }
    /**
     * Читает обязательную строку field из node; числа, boolean, null и отсутствие поля отклоняет без
     * преобразования.
     *
     * @param node проверяемый узел JSON
     * @param field имя читаемого поля
     */
    private String text(JsonNode node,String field) {
        require(node.path(field).isTextual(),"Invalid or missing "+field); return node.get(field).textValue();
    }
    /**
     * Проверяет объект node и разрешённые имена allowed; неизвестные поля отклоняет без отражения входных имён
     * в ошибке.
     *
     * @param node проверяемый узел JSON
     * @param allowed разрешённые имена полей объекта
     */
    private void fields(JsonNode node,Set<String> allowed) {
        require(node!=null && node.isObject(),"Expected JSON object");
        node.fieldNames().forEachRemaining(field -> require(allowed.contains(field),"Unknown field"));
    }
    /**
     * Читает один JSON-документ raw; пустой ввод, повторные ключи и повреждённый JSON вызывают
     * VALIDATION_ERROR.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public JsonNode read(String raw) {
        try {
            JsonNode node=mapper.readTree(raw);
            if(node==null || node.isMissingNode()) throw invalid("Empty JSON document");
            return node;
        } catch(Exception failure) { throw invalid("Malformed JSON"); }
    }
    /**
     * Сериализует value в согласованный JSON для хранения; сбой сериализации прерывает текущую операцию.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception failure) { throw new IllegalStateException("Cannot serialize stock receipt",failure); }
    }
    /**
     * Возвращает отпечаток нормализованного payload с устойчивым порядком полей и строк.
     *
     * @param payload сериализованное содержимое события или проверенный объект согласно типу
     */
    private String fingerprint(PostedPayload payload) {
        return hash(json(payload));
    }
    /**
     * Возвращает детерминированный SHA-256 строки value в UTF-8; недоступный алгоритм вызывает
     * IllegalStateException.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    /**
     * Создаёт безопасную ошибку VALIDATION_ERROR с переданным пояснением message.
     *
     * @param message безопасное пояснение без секретов
     */
    private ShopException invalid(String message) { return new ShopException(400,"VALIDATION_ERROR",message); }
    /**
     * Проверяет condition; при нарушении выбрасывает VALIDATION_ERROR с безопасным message.
     *
     * @param condition проверяемый инвариант протокола
     * @param message безопасное пояснение без секретов
     */
    private void require(boolean condition,String message) { if(!condition) throw invalid(message); }
}
