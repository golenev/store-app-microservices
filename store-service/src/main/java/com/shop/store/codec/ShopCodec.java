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

/**
 * Проверяет JSON-запросы и события магазина и переводит их в Java-объекты. Цены и суммы обрабатывает как
 * точные десятичные значения.
 */
@Component
public class ShopCodec {
    public static final long MAX_VERSION=9007199254740991L;
    private static final String MONEY="(0|[1-9][0-9]{0,25})\\.[0-9]{2}";
    private final ObjectMapper mapper;
    /**
     * Создаёт отдельную настройку чтения JSON: повторные поля, неизвестные поля при чтении Java-модели и
     * данные после первого документа считаются ошибкой.
     *
     * @param mapper настройки преобразования Java-объектов и JSON
     */
    public ShopCodec(ObjectMapper mapper) {
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    /**
     * Читает событие оприходования {@code GoodsPosted} и проверяет строки, цены и даты. Приводит наценку к
     * шести знакам после точки и сортирует строки по {@code lineId}. Возвращает событие и контрольную сумму
     * содержимого поставки для сравнения повторов; неверные данные отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param raw JSON-событие {@code GoodsPosted} из Kafka
     * @return проверенное событие и контрольная сумма содержимого поставки
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
     * Читает новое итоговое количество товара и ожидаемую версию корзины. Разрешает только эти два поля;
     * неверные значения или лишние поля отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param raw JSON с полями {@code quantity} и {@code expectedCartVersion}
     * @return проверенное количество товара и ожидаемая версия корзины
     */
    public PutItem put(String raw) {
        JsonNode node=read(raw); fields(node,Set.of("quantity","expectedCartVersion"));
        return new PutItem((int)integer(node.get("quantity"),1,Integer.MAX_VALUE),integer(node.get("expectedCartVersion"),0,MAX_VERSION));
    }
    /**
     * Читает ожидаемую версию корзины для оформления. Состав корзины и цены клиент передать не может:
     * дополнительные поля отклоняются.
     *
     * @param raw JSON с единственным полем {@code expectedCartVersion}
     * @return проверенная ожидаемая версия оформления
     */
    public SubmitInput submit(String raw) {
        JsonNode node=read(raw); fields(node,Set.of("expectedCartVersion"));
        return new SubmitInput(integer(node.get("expectedCartVersion"),0,MAX_VERSION));
    }
    /**
     * Проверяет ключ, по которому магазин распознаёт повтор оформления. Допускает от 1 до 128 латинских букв,
     * цифр и символов {@code . _ : -}; регистр сохраняет. При нарушении формата выдаёт {@code
     * VALIDATION_ERROR}.
     *
     * @param value ключ повтора оформления из заголовка {@code Idempotency-Key}
     * @return проверенный ключ повтора без изменения регистра и символов
     */
    public String idempotencyKey(String value) {
        require(value!=null && value.matches("[A-Za-z0-9._:-]{1,128}"),"Invalid Idempotency-Key"); return value;
    }
    /**
     * Вычисляет контрольную сумму SHA-256 магазина, корзины и ожидаемой версии. По ней можно отличить повтор
     * того же запроса от другого запроса с прежним ключом; цены и состав корзины в расчёт не входят.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param version версия корзины
     * @return контрольная сумма параметров оформления в шестнадцатеричной записи
     */
    public String submissionFingerprint(String store,UUID cart,long version) {
        return hash(json(List.of(store,cart.toString(),version)));
    }
    /**
     * Читает сохранённый при оформлении снимок корзины. Если JSON нельзя восстановить, выдаёт {@code
     * DEPENDENCY_UNAVAILABLE}, поскольку принятый заказ нельзя достоверно показать клиенту.
     *
     * @param raw JSON-снимок оформленной корзины из БД
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    public Cart cartSnapshot(String raw) {
        try { return mapper.readValue(raw,Cart.class); }
        catch(Exception failure) { throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart snapshot unavailable"); }
    }
    /**
     * Преобразует строку версии в целое число от 0 до {@code MAX_VERSION}. Дроби, знаки, ведущие нули у
     * ненулевого числа и значения за пределами диапазона отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value ожидаемая версия корзины из параметра HTTP-запроса
     * @return проверенная версия корзины от 0 до {@code MAX_VERSION}
     */
    public long version(String value) {
        require(value!=null && value.matches("(0|[1-9][0-9]{0,15})"),"Invalid expectedCartVersion");
        long number=Long.parseLong(value); require(number<=MAX_VERSION,"Invalid expectedCartVersion"); return number;
    }
    /**
     * Проверяет идентификатор длиной от 1 до 64 символов. Первый символ должен быть латинской буквой или
     * цифрой; далее разрешены также {@code . _ : -}. Возвращает исходную строку, а нарушение формата отклоняет
     * с {@code VALIDATION_ERROR}.
     *
     * @param value проверяемый идентификатор магазина, продукта или строки
     * @return допустимый идентификатор без изменений
     */
    public String identifier(String value) {
        require(value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"),"Invalid identifier"); return value;
    }
    /**
     * Преобразует строку в UUID только в полном формате из пяти групп: 8, 4, 4, 4 и 12 шестнадцатеричных цифр.
     * Сокращённую или неверную запись отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value строковая запись UUID
     * @return UUID, прочитанный из полной строковой записи
     */
    public UUID uuid(String value) {
        require(value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"Invalid UUID");
        return UUID.fromString(value);
    }
    /**
     * Проверяет положительную цену с ровно двумя цифрами после точки и не более чем 26 цифрами до неё.
     * Возвращает исходную строку без округления; неверный формат или ноль отклоняет с {@code
     * VALIDATION_ERROR}.
     *
     * @param value проверяемая строковая цена
     * @return проверенная положительная цена без округления
     */
    private String price(String value) {
        require(value.matches(MONEY) && new BigDecimal(value).signum()>0,"Invalid price"); return value;
    }
    /**
     * Преобразует строку даты в {@code Instant}. Требует окончание {@code Z}, обозначающее время UTC; неверную
     * дату отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value строковая дата с окончанием {@code Z}
     * @return момент времени; для отсутствующей даты в БД {@code null}
     */
    private Instant utc(String value) {
        require(value.endsWith("Z"),"Timestamp must be UTC");
        try { return Instant.parse(value); } catch(RuntimeException failure) { throw invalid("Invalid timestamp"); }
    }
    /**
     * Читает целое число из JSON и проверяет заданные границы включительно. Отсутствие значения, {@code null},
     * дробь, переполнение или выход за границы вызывают {@code VALIDATION_ERROR}.
     *
     * @param node JSON-значение, которое нужно проверить или преобразовать
     * @param lower минимальное допустимое целое число, включительно
     * @param upper максимальное допустимое целое число, включительно
     * @return целое число внутри заданных границ
     */
    private long integer(JsonNode node,long lower,long upper) {
        require(node!=null && node.isIntegralNumber() && node.canConvertToLong(),"Invalid integer");
        long value=node.longValue(); require(value>=lower && value<=upper,"Integer out of range"); return value;
    }
    /**
     * Читает обязательное строковое поле JSON. Отсутствие поля, {@code null}, число или логическое значение
     * вызывают {@code VALIDATION_ERROR}; автоматического преобразования в строку нет.
     *
     * @param node JSON-значение, которое нужно проверить или преобразовать
     * @param field имя читаемого поля
     * @return строковое значение обязательного поля
     */
    private String text(JsonNode node,String field) {
        require(node.path(field).isTextual(),"Invalid or missing "+field); return node.get(field).textValue();
    }
    /**
     * Проверяет, что передан JSON-объект и все его поля входят в разрешённый набор. При лишнем поле выдаёт
     * {@code VALIDATION_ERROR}, не включая его имя в сообщение.
     *
     * @param node JSON-значение, которое нужно проверить или преобразовать
     * @param allowed разрешённые имена полей JSON-объекта
     */
    private void fields(JsonNode node,Set<String> allowed) {
        require(node!=null && node.isObject(),"Expected JSON object");
        node.fieldNames().forEachRemaining(field -> require(allowed.contains(field),"Unknown field"));
    }
    /**
     * Читает ровно один JSON-документ. Пустое тело, повторные имена полей, повреждённый JSON или данные после
     * документа отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param raw исходный текст JSON-документа
     * @return прочитанное или упорядоченное JSON-значение
     */
    public JsonNode read(String raw) {
        try {
            JsonNode node=mapper.readTree(raw);
            if(node==null || node.isMissingNode()) throw invalid("Empty JSON document");
            return node;
        } catch(Exception failure) { throw invalid("Malformed JSON"); }
    }
    /**
     * Преобразует объект в JSON для сохранения или отправки. Если преобразование невозможно, выбрасывает
     * {@code IllegalStateException} и прерывает текущую операцию.
     *
     * @param value Java-объект для преобразования в JSON
     * @return JSON-запись переданного объекта
     */
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception failure) { throw new IllegalStateException("Cannot serialize stock receipt",failure); }
    }
    /**
     * Вычисляет контрольную сумму уже упорядоченного содержимого поставки. Одинаковые цены, даты и строки дают
     * одинаковую сумму для проверки повторного прихода.
     *
     * @param payload проверенное содержимое оприходованной поставки с упорядоченными строками
     * @return контрольная сумма содержимого поставки в шестнадцатеричной записи
     */
    private String fingerprint(PostedPayload payload) {
        return hash(json(payload));
    }
    /**
     * Вычисляет SHA-256 строки в кодировке UTF-8 и возвращает шестнадцатеричную запись. Если алгоритм
     * недоступен, выбрасывает {@code IllegalStateException}.
     *
     * @param value текст, для которого вычисляется контрольная сумма
     * @return контрольная сумма SHA-256 в шестнадцатеричной записи
     */
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    /**
     * Создаёт ошибку HTTP 400 с кодом {@code VALIDATION_ERROR} и переданным пояснением. Сам метод ошибку не
     * выбрасывает.
     *
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @return исключение магазина с подготовленными статусом, кодом и пояснением
     */
    private ShopException invalid(String message) { return new ShopException(400,"VALIDATION_ERROR",message); }
    /**
     * Продолжает проверку, если условие выполнено. Иначе выбрасывает ошибку HTTP 400 с кодом {@code
     * VALIDATION_ERROR} и переданным пояснением.
     *
     * @param condition условие, которое должно выполняться для допустимых данных
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    private void require(boolean condition,String message) { if(!condition) throw invalid(message); }
}
