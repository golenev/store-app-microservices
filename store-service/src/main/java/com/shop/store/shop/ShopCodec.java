package com.shop.store.shop;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.shop.store.shop.ShopModels.*;

/** Validates financial GoodsPosted and cart input explicitly before storage; no numeric/string coercion is accepted. */
@Component
public class ShopCodec {
    public static final long MAX_VERSION=9007199254740991L;
    private static final String MONEY="(0|[1-9][0-9]{0,25})\\.[0-9]{2}";
    private final ObjectMapper mapper;
    /** Copies Spring's UTC serializer, requiring one document with no unknown or duplicate JSON keys. */
    public ShopCodec(ObjectMapper mapper) {
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    /** Parses one fully valid event and normalizes rate/timestamps/order for a business fingerprint excluding eventId. */
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
    /** Parses a full replacement cart quantity with the required expected version, forbidding client prices/totals. */
    public PutItem put(String raw) {
        JsonNode node=read(raw); fields(node,Set.of("quantity","expectedCartVersion"));
        return new PutItem((int)integer(node.get("quantity"),1,Integer.MAX_VALUE),integer(node.get("expectedCartVersion"),0,MAX_VERSION));
    }
    /** Parses only the expected composition version; callers cannot provide purchase prices, totals or an alternative cart. */
    public SubmitInput submit(String raw) {
        JsonNode node=read(raw); fields(node,Set.of("expectedCartVersion"));
        return new SubmitInput(integer(node.get("expectedCartVersion"),0,MAX_VERSION));
    }
    /** Validates a case-sensitive store-scoped operation key, which must be reused after an uncertain HTTP outcome. */
    public String idempotencyKey(String value) {
        require(value!=null && value.matches("[A-Za-z0-9._:-]{1,128}"),"Invalid Idempotency-Key"); return value;
    }
    /** Hashes a canonical request tuple; cart composition/prices are server-owned and deliberately absent from the request. */
    public String submissionFingerprint(String store,UUID cart,long version) {
        return hash(json(List.of(store,cart.toString(),version)));
    }
    /** Reads a server-persisted immutable submitted-cart snapshot, failing safely if storage is corrupt. */
    public Cart cartSnapshot(String raw) {
        try { return mapper.readValue(raw,Cart.class); }
        catch(Exception failure) { throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart snapshot unavailable"); }
    }
    /** Validates a canonical query version without overflow or floating point; missing values are client errors. */
    public long version(String value) {
        require(value!=null && value.matches("(0|[1-9][0-9]{0,15})"),"Invalid expectedCartVersion");
        long number=Long.parseLong(value); require(number<=MAX_VERSION,"Invalid expectedCartVersion"); return number;
    }
    /** Validates a case-sensitive store/product/delivery protocol identifier. */
    public String identifier(String value) {
        require(value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"),"Invalid identifier"); return value;
    }
    /** Rejects Java's permissive abbreviated UUID representations before parsing. */
    public UUID uuid(String value) {
        require(value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"Invalid UUID");
        return UUID.fromString(value);
    }
    /** Requires a positive fixed-scale decimal price within NUMERIC(28,2) wire bounds. */
    private String price(String value) {
        require(value.matches(MONEY) && new BigDecimal(value).signum()>0,"Invalid price"); return value;
    }
    /** Parses UTC timestamps; equal instants normalize textual fractional-second differences in fingerprints. */
    private Instant utc(String value) {
        require(value.endsWith("Z"),"Timestamp must be UTC");
        try { return Instant.parse(value); } catch(RuntimeException failure) { throw invalid("Invalid timestamp"); }
    }
    /** Validates exact integral JSON values with a bounded long conversion, rejecting null and decimal coercion. */
    private long integer(JsonNode node,long lower,long upper) {
        require(node!=null && node.isIntegralNumber() && node.canConvertToLong(),"Invalid integer");
        long value=node.longValue(); require(value>=lower && value<=upper,"Integer out of range"); return value;
    }
    /** Reads only string values; numeric/boolean/null/missing fields are never coerced into valid business strings. */
    private String text(JsonNode node,String field) {
        require(node.path(field).isTextual(),"Invalid or missing "+field); return node.get(field).textValue();
    }
    /** Rejects unexpected object fields without echoing attacker-supplied field names into diagnostics. */
    private void fields(JsonNode node,Set<String> allowed) {
        require(node!=null && node.isObject(),"Expected JSON object");
        node.fieldNames().forEachRemaining(field -> require(allowed.contains(field),"Unknown field"));
    }
    /** Parses one JSON document; tombstones, empty documents and malformed/duplicate-key JSON become safe diagnostics. */
    public JsonNode read(String raw) {
        try {
            JsonNode node=mapper.readTree(raw);
            if(node==null || node.isMissingNode()) throw invalid("Empty JSON document");
            return node;
        } catch(Exception failure) { throw invalid("Malformed JSON"); }
    }
    /** Serializes normalized persisted receipt evidence; failures abort the receipt transaction. */
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception failure) { throw new IllegalStateException("Cannot serialize stock receipt",failure); }
    }
    /** Hashes the normalized record, whose declared field order and sorted items are stable across transport representations. */
    private String fingerprint(PostedPayload payload) {
        return hash(json(payload));
    }
    /** Computes deterministic UTF-8 SHA-256 without exposing request keys in stored diagnostic messages. */
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    /** Builds safe named validation errors suitable for both API and persisted diagnostics. */
    private ShopException invalid(String message) { return new ShopException(400,"VALIDATION_ERROR",message); }
    /** Enforces a protocol constraint before storage or financial calculation. */
    private void require(boolean condition,String message) { if(!condition) throw invalid(message); }
}
