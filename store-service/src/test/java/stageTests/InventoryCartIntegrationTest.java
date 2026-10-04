package stageTests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.shop.store.StoreServiceApplication;
import com.shop.store.shop.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.shop.store.shop.ShopModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP/Kafka/PostgreSQL tests with independent store fixtures; no tariffs HTTP mock is needed because STORE no longer calls it. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=StoreServiceApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.properties.max.request.size=16777216"})
class InventoryCartIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer broker=new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ShopCodec codec;
    @Autowired CartService carts;
    @Autowired KafkaTemplate<String,String> kafka;
    @SpyBean GoodsReceiver receiver;
    private String store,otherStore;
    private long coordinate;

    /** Supplies isolated real infrastructure; Flyway remains the only schema creator. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers",broker::getBootstrapServers);
    }
    /** Creates two independent store scopes and unique event coordinates, avoiding shared mutable test baskets. */
    @BeforeEach
    void setup() {
        store="T-"+UUID.randomUUID(); otherStore="T-"+UUID.randomUUID();
        coordinate=Math.abs(UUID.randomUUID().getLeastSignificantBits());
        jdbc.update("INSERT INTO store_scopes(store_id) VALUES(?),(?)",store,otherStore);
        clearInvocations(receiver);
    }
    /** Removes only this scenario's fixtures in FK order and all test-only fault triggers. */
    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_movement ON stock_movements");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_diagnostic ON incoming_goods_diagnostics");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_cart_version ON carts");
        for(String scope:List.of(store,otherStore)) {
            jdbc.update("DELETE FROM cart_items WHERE store_id=?",scope);
            jdbc.update("DELETE FROM carts WHERE store_id=?",scope);
            jdbc.update("DELETE FROM stock_movements WHERE store_id=?",scope);
            jdbc.update("DELETE FROM processed_events WHERE store_id=?",scope);
            jdbc.update("DELETE FROM inventory WHERE store_id=?",scope);
            jdbc.update("DELETE FROM stock_receipts WHERE store_id=?",scope);
            jdbc.update("DELETE FROM store_scopes WHERE store_id=?",scope);
        }
    }

    /** Real Kafka creates one scoped SKU; an independent HTTP cart uses current prices without changing quantity. */
    @Test
    void kafkaReceiptAndPublicCartApiWorkTogether() throws Exception {
        ObjectNode input=event(1,10,"100.00","0.20");
        kafka.send("warehouse.goods-posted",store,codec.json(input)).get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count("inventory")).isEqualTo(1));
        var catalog=http.getForEntity("/stores/"+store+"/catalog",JsonNode.class);
        assertThat(catalog.getStatusCode().value()).isEqualTo(200);
        assertThat(catalog.getBody().path("items").get(0).path("unitPrice").asText()).isEqualTo("120.00");
        var created=http.postForEntity("/stores/"+store+"/carts",null,JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID cart=UUID.fromString(created.getBody().path("cartId").asText());
        assertThat(created.getHeaders().getLocation().toString()).endsWith(cart.toString());
        assertThat(created.getBody().path("version").asLong()).isZero();
        var added=put(cart,stock().stockItemId(),3,0);
        assertThat(added.getStatusCode().value()).isEqualTo(200);
        assertThat(added.getBody().path("totalAmount").asText()).isEqualTo("360.00");
        assertThat(added.getBody().path("version").asLong()).isEqualTo(1);
        assertThat(stock().availableQuantity()).isEqualTo(10);
        assertThat(count("stock_receipts")).isEqualTo(1);
        assertThat(count("stock_movements")).isEqualTo(1);
    }

    /** Replayed event and a new transport UUID normalize order/rate/timestamps to one receipt and one movement per line. */
    @Test
    void duplicateBusinessPayloadWithNewEventNeverAddsStockTwice() {
        ObjectNode input=event(1,10,"100.00","0.20"); addLine(input,"L-2","P-2");
        receive(input); receive(input);
        ObjectNode changed=input.deepCopy(); changed.put("eventId",UUID.randomUUID().toString());
        ObjectNode payload=(ObjectNode)changed.path("payload");
        payload.put("postedAt","2026-10-03T10:00:02.000Z"); changed.put("occurredAt","2026-10-03T10:00:02.000Z");
        ArrayNode lines=(ArrayNode)payload.get("items"); JsonNode first=lines.remove(0); lines.add(first);
        ((ObjectNode)lines.get(0)).put("markupRate","0.200000"); receive(changed);
        assertThat(count("inventory")).isEqualTo(2);
        assertThat(count("stock_receipts")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(count("stock_movements")).isEqualTo(2);
        assertThat(carts.catalog(store).items()).allSatisfy(item -> assertThat(item.availableQuantity()).isEqualTo(10));
    }

    /** A later unique delivery merges quantity into the same SKU and replaces price/name/description for the entire balance. */
    @Test
    void replenishmentKeepsSkuAndRepricesAllRemainingUnits() {
        receive(event(1,6,"100.00","0.20")); Stock before=stock();
        UUID cart=carts.create(store).cartId(); carts.put(store,cart,before.stockItemId(),new PutItem(3,0));
        ObjectNode second=event(2,10,"120.00","0.20");
        ((ObjectNode)second.path("payload").path("items").get(0)).put("shortName","New name").put("description","New description");
        receive(second); Stock after=stock();
        assertThat(after.stockItemId()).isEqualTo(before.stockItemId());
        assertThat(after.availableQuantity()).isEqualTo(16);
        assertThat(after.unitPrice()).isEqualTo("144.00");
        assertThat(after.shortName()).isEqualTo("New name"); assertThat(after.description()).isEqualTo("New description");
        Cart view=carts.get(store,cart);
        assertThat(view.version()).isEqualTo(1); assertThat(view.totalAmount()).isEqualTo("432.00");
        assertThat(view.items().getFirst().shortName()).isEqualTo("New name");
    }

    /** An older delayed receipt adds its unique quantity but never rolls back the newer price or displayed metadata. */
    @Test
    void delayedOlderDeliveryPreservesLatestSequencePrice() {
        ObjectNode latest=event(2,10,"120.00","0.20");
        ((ObjectNode)latest.path("payload").path("items").get(0)).put("shortName","Latest"); receive(latest);
        UUID id=stock().stockItemId(); receive(event(1,6,"100.00","0.20")); receive(latest);
        assertThat(stock().availableQuantity()).isEqualTo(16); assertThat(stock().unitPrice()).isEqualTo("144.00");
        assertThat(stock().shortName()).isEqualTo("Latest"); assertThat(stock().stockItemId()).isEqualTo(id);
        assertThat(count("stock_receipts")).isEqualTo(2); assertThat(count("stock_movements")).isEqualTo(2);
    }

    /** Conflicting content remains diagnosed and cannot overwrite a receipt even under a fresh transport UUID. */
    @ParameterizedTest
    @ValueSource(strings={"quantity","price","shortName","description","productId","lineId","productType","tariffVersion","ruleId","receivedAt","postedAt","sequence"})
    void changedContentCannotOverwriteReceipt(String field) {
        ObjectNode original=event(1,10,"100.00","0.20"); receive(original);
        ObjectNode changed=original.deepCopy(); changed.put("eventId",UUID.randomUUID().toString());
        ObjectNode payload=(ObjectNode)changed.get("payload"),line=(ObjectNode)payload.path("items").get(0);
        switch(field) {
            case "quantity" -> line.put("quantity",11);
            case "price" -> { line.put("purchasePrice","110.00"); line.put("salePrice","132.00"); }
            case "shortName","description","productId","lineId" -> line.put(field,"Changed");
            case "productType" -> line.put("productType","FOOD");
            case "tariffVersion" -> line.put("tariffVersion",2);
            case "ruleId" -> line.put("tariffRuleId",UUID.randomUUID().toString());
            case "receivedAt" -> payload.put("receivedAt","2026-10-03T10:00:00Z");
            case "postedAt" -> { payload.put("postedAt","2026-10-03T10:00:03Z"); changed.put("occurredAt","2026-10-03T10:00:03Z"); }
            case "sequence" -> payload.put("deliverySequence",2);
            default -> throw new AssertionError(field);
        }
        receive(changed);
        assertDiagnostic("DELIVERY_CONTENT_CONFLICT");
        assertThat(stock().availableQuantity()).isEqualTo(10); assertThat(stock().unitPrice()).isEqualTo("120.00");
        assertThat(count("processed_events")).isEqualTo(1); assertThat(count("stock_movements")).isEqualTo(1);
    }

    /** A globally reused event UUID cannot migrate into another store; identical product IDs remain otherwise store scoped. */
    @Test
    void eventIdCannotChangeStoreOwnership() {
        ObjectNode original=event(1,10,"100.00","0.20"); receive(original);
        original.put("storeId",otherStore);
        receiver.receive("test",0,++coordinate,otherStore,codec.json(original));
        assertDiagnostic("DELIVERY_CONTENT_CONFLICT");
        assertThat(carts.catalog(otherStore).items()).isEmpty();
        ObjectNode independent=event(1,5,"200.00","0.20"); independent.put("storeId",otherStore);
        receiver.receive("test",0,++coordinate,otherStore,codec.json(independent));
        assertThat(carts.catalog(otherStore).items().getFirst().unitPrice()).isEqualTo("240.00");
        assertThat(stock().unitPrice()).isEqualTo("120.00");
    }

    /** Reusing a delivery sequence for different content is diagnosed, preventing ambiguous last-price ordering. */
    @Test
    void sequenceCannotBelongToTwoDeliveries() {
        receive(event(1,10,"100.00","0.20")); receive(event(1,5,"200.00","0.20"));
        assertDiagnostic("DELIVERY_CONTENT_CONFLICT"); assertThat(stock().availableQuantity()).isEqualTo(10);
    }

    /** Changed product type across a new delivery rejects all lines and leaves existing inventory intact. */
    @Test
    void inconsistentTypeRejectsWholeNewDelivery() {
        receive(event(1,10,"100.00","0.20")); ObjectNode changed=event(2,3,"100.00","0.20");
        ((ObjectNode)changed.path("payload").path("items").get(0)).put("productType","FOOD"); addLine(changed,"L-2","P-2");
        receive(changed); assertDiagnostic("DELIVERY_CONTENT_CONFLICT");
        assertThat(count("inventory")).isEqualTo(1); assertThat(stock().availableQuantity()).isEqualTo(10);
        assertThat(count("stock_receipts")).isEqualTo(1);
    }

    /** Quantity overflow rejects the entire new receipt, without a partial second product or processed marker. */
    @Test
    void quantityOverflowIsAtomic() {
        receive(event(1,Integer.MAX_VALUE,"100.00","0.20")); ObjectNode overflow=event(2,1,"100.00","0.20");
        addLine(overflow,"L-2","P-2"); receive(overflow); assertDiagnostic("VALIDATION_ERROR");
        assertThat(stock().availableQuantity()).isEqualTo(Integer.MAX_VALUE); assertThat(count("inventory")).isEqualTo(1);
        assertThat(count("stock_receipts")).isEqualTo(1);
    }

    /** Concurrent different deliveries and simultaneous duplicates add each quantity once and keep one stable latest-priced SKU. */
    @Test
    void concurrentReceiptsAndDuplicatesHaveOneSku() throws Exception {
        ObjectNode first=event(1,3,"100.00","0.20"),second=event(2,4,"120.00","0.20");
        try(ExecutorService pool=Executors.newFixedThreadPool(3)) {
            CountDownLatch start=new CountDownLatch(1);
            var a=pool.submit(() -> { awaitStart(start); receiver.receive("test",0,coordinate,store,codec.json(first)); });
            var b=pool.submit(() -> { awaitStart(start); receiver.receive("test",0,coordinate+1,store,codec.json(second)); });
            var c=pool.submit(() -> { awaitStart(start); receiver.receive("test",0,coordinate+2,store,codec.json(first)); });
            start.countDown(); a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS); c.get(10,TimeUnit.SECONDS);
        }
        assertThat(count("inventory")).isEqualTo(1); assertThat(count("stock_movements")).isEqualTo(2);
        assertThat(stock().availableQuantity()).isEqualTo(7); assertThat(stock().unitPrice()).isEqualTo("144.00");
    }

    /** Any SQL failure after inventory modification rolls back receipt, amount, movement and event marker together. */
    @Test
    void movementFailureRollsBackReceiptAndQuantity() {
        receive(event(1,10,"100.00","0.20")); ObjectNode second=event(2,5,"200.00","0.20");
        failingTrigger("stock_movements","fail_movement","INSERT");
        assertThatThrownBy(() -> receive(second)).isInstanceOf(DataAccessException.class);
        assertThat(stock().availableQuantity()).isEqualTo(10); assertThat(stock().unitPrice()).isEqualTo("120.00");
        assertThat(count("stock_receipts")).isEqualTo(1); assertThat(count("processed_events")).isEqualTo(1);
        jdbc.execute("DROP TRIGGER fail_movement ON stock_movements"); receive(second);
        assertThat(stock().availableQuantity()).isEqualTo(15); assertThat(stock().unitPrice()).isEqualTo("240.00");
    }

    /** Two baskets can independently contain the entire balance; rejected excess PUT changes neither basket version nor stock. */
    @Test
    void independentCartsNeverReserveInventory() {
        receive(event(1,5,"100.00","0.20")); UUID stock=stock().stockItemId();
        Cart a=carts.create(store),b=carts.create(store);
        assertThat(a.cartId()).isNotEqualTo(b.cartId());
        assertThat(put(a.cartId(),stock,5,0).getStatusCode().value()).isEqualTo(200);
        assertThat(put(b.cartId(),stock,5,0).getStatusCode().value()).isEqualTo(200);
        assertError(put(a.cartId(),stock,6,1),409,"INSUFFICIENT_STOCK");
        assertThat(carts.get(store,a.cartId()).version()).isEqualTo(1);
        assertThat(carts.get(store,b.cartId()).items().getFirst().quantity()).isEqualTo(5);
        assertThat(stock().availableQuantity()).isEqualTo(5);
        assertThat(count("stock_movements")).isEqualTo(1);
    }

    /** PUT sets the total quantity, increments even on unchanged value, and requires the current version for delete. */
    @Test
    void replacementAndDeletionUseExpectedVersion() {
        receive(event(1,10,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        put(cart,id,3,0); var same=put(cart,id,3,1);
        assertThat(same.getBody().path("version").asLong()).isEqualTo(2);
        assertThat(same.getBody().path("items").get(0).path("quantity").asInt()).isEqualTo(3);
        assertError(delete(cart,id,1),409,"CART_VERSION_CONFLICT");
        assertThat(delete(cart,id,2).getBody().path("version").asLong()).isEqualTo(3);
        assertError(delete(cart,id,3),404,"NOT_FOUND");
        assertThat(carts.get(store,cart).version()).isEqualTo(3); assertThat(carts.get(store,cart).items()).isEmpty();
        assertThat(stock().availableQuantity()).isEqualTo(10);
    }

    /** Simultaneous edits from version zero have one winner; no lost update, duplicate line or implicit quantity addition occurs. */
    @Test
    void concurrentCartPutsHaveOneVersionWinner() throws Exception {
        receive(event(1,10,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            var first=pool.submit(() -> { awaitStart(start); return put(cart,id,3,0).getStatusCode().value(); });
            var second=pool.submit(() -> { awaitStart(start); return put(cart,id,4,0).getStatusCode().value(); });
            start.countDown(); assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(carts.get(store,cart).version()).isEqualTo(1); assertThat(carts.get(store,cart).items()).hasSize(1);
        assertThat(stock().availableQuantity()).isEqualTo(10);
    }

    /** A cart is scoped independently from inventory; knowing another store's UUID does not expose or mutate its data. */
    @Test
    void storesAndCartsAreIsolated() {
        receive(event(1,10,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        UUID foreign=carts.create(otherStore).cartId();
        assertError(http.getForEntity("/stores/"+otherStore+"/carts/"+cart,JsonNode.class),404,"NOT_FOUND");
        assertError(request(HttpMethod.PUT,"/stores/"+otherStore+"/carts/"+foreign+"/items/"+id,putBody(1,0)),404,"NOT_FOUND");
        assertError(put(foreign,id,1,0),404,"NOT_FOUND");
        assertThat(carts.get(otherStore,foreign).version()).isZero(); assertThat(carts.get(store,cart).version()).isZero();
    }

    /** Zero inventory remains in catalog; availability is rechecked on every PUT and deleting an existing line remains allowed. */
    @Test
    void zeroStockRemainsVisibleAndCannotBeAdded() {
        receive(event(1,5,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        put(cart,id,2,0); jdbc.update("UPDATE inventory SET available_quantity=0 WHERE stock_item_id=?",id);
        assertThat(stock().availableQuantity()).isZero();
        assertError(put(cart,id,1,1),409,"INSUFFICIENT_STOCK");
        assertThat(delete(cart,id,1).getStatusCode().value()).isEqualTo(200);
        assertThat(stock().stockItemId()).isEqualTo(id);
    }

    /** Closed carts reject edit attempts and exhausted versions never wrap or perform a partial line write. */
    @Test
    void closedAndExhaustedCartsCannotMutate() {
        receive(event(1,5,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        jdbc.update("UPDATE carts SET state='SUBMITTED' WHERE cart_id=?",cart);
        assertError(put(cart,id,1,0),409,"CART_ALREADY_SUBMITTED");
        jdbc.update("UPDATE carts SET state='OPEN',version=? WHERE cart_id=?",ShopCodec.MAX_VERSION,cart);
        assertError(put(cart,id,1,ShopCodec.MAX_VERSION),400,"VALIDATION_ERROR");
        assertThat(count("cart_items")).isZero();
    }

    /** A failure after the line upsert but before version update rolls back both composition and version. */
    @Test
    void cartStorageFailureRollsBackCompositionAndVersion() {
        receive(event(1,5,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        failingTrigger("carts","fail_cart_version","UPDATE");
        assertError(put(cart,id,2,0),503,"DEPENDENCY_UNAVAILABLE");
        assertThat(carts.get(store,cart).items()).isEmpty(); assertThat(carts.get(store,cart).version()).isZero();
    }

    /** Strict PUT parsing rejects omitted/extra/duplicate fields and coerced numbers before any version or composition change. */
    @ParameterizedTest
    @ValueSource(strings={"{}","{\"quantity\":1}","{\"quantity\":0,\"expectedCartVersion\":0}","{\"quantity\":2147483648,\"expectedCartVersion\":0}",
            "{\"quantity\":1.5,\"expectedCartVersion\":0}","{\"quantity\":\"1\",\"expectedCartVersion\":0}","{\"quantity\":1,\"expectedCartVersion\":-1}",
            "{\"quantity\":1,\"expectedCartVersion\":9007199254740992}","{\"quantity\":1,\"quantity\":2,\"expectedCartVersion\":0}",
            "{\"quantity\":1,\"expectedCartVersion\":0,\"unitPrice\":\"1.00\"}","null","{"})
    void invalidCartJsonNeverChangesState(String raw) {
        receive(event(1,5,"100.00","0.20")); UUID id=stock().stockItemId(),cart=carts.create(store).cartId();
        assertError(request(HttpMethod.PUT,"/stores/"+store+"/carts/"+cart+"/items/"+id,raw),400,"VALIDATION_ERROR");
        assertThat(carts.get(store,cart).version()).isZero(); assertThat(count("cart_items")).isZero();
    }

    /** Poison GoodsPosted shapes/values are durable diagnostics with no inventory, receipt, event or movement changes. */
    @ParameterizedTest
    @ValueSource(strings={"broken","empty","null","tombstone","duplicateKey","unknownVersion","wrappedVersion","type","uuid","key","unknownField",
            "numericPrice","zeroPrice","zeroQuantity","overflowQuantity","decimalQuantity","blankName","nonUtc","timeOrder","occurredMismatch",
            "wrongFormula","negativeRate","badCurrency","duplicateProduct","duplicateLine","badRule","zeroTariffVersion","emptyItems"})
    void invalidGoodsAreDiagnosedWithoutStock(String kind) {
        ObjectNode input=event(1,10,"100.00","0.20"); ObjectNode payload=(ObjectNode)input.get("payload");
        ObjectNode line=(ObjectNode)payload.path("items").get(0); String key=store;
        switch(kind) {
            case "unknownVersion" -> input.put("schemaVersion",2);
            case "wrappedVersion" -> input.set("schemaVersion",new BigIntegerNode(new java.math.BigInteger("18446744073709551617")));
            case "type" -> input.put("eventType","DeliveryReceived");
            case "uuid" -> input.put("eventId","1-1-1-1-1");
            case "key" -> key=otherStore;
            case "unknownField" -> line.put("extra",true);
            case "numericPrice" -> line.put("purchasePrice",100);
            case "zeroPrice" -> line.put("salePrice","0.00");
            case "zeroQuantity" -> line.put("quantity",0);
            case "overflowQuantity" -> line.put("quantity",2147483648L);
            case "decimalQuantity" -> line.put("quantity",1.2);
            case "blankName" -> line.put("shortName"," ");
            case "nonUtc" -> payload.put("receivedAt","2026-10-03T10:00:01+00:00");
            case "timeOrder" -> payload.put("receivedAt","2026-10-03T10:00:03Z");
            case "occurredMismatch" -> input.put("occurredAt","2026-10-03T10:00:03Z");
            case "wrongFormula" -> line.put("salePrice","121.00");
            case "negativeRate" -> line.put("markupRate","-0.20");
            case "badCurrency" -> line.put("currency","USD");
            case "duplicateProduct" -> addLine(input,"L-2","P-1");
            case "duplicateLine" -> addLine(input,"L-1","P-2");
            case "badRule" -> line.put("tariffRuleId","1-1-1-1-1");
            case "zeroTariffVersion" -> line.put("tariffVersion",0);
            case "emptyItems" -> ((ArrayNode)payload.get("items")).removeAll();
            default -> { }
        }
        String raw=codec.json(input);
        if(kind.equals("broken")) raw="{";
        if(kind.equals("empty")) raw=" ";
        if(kind.equals("null")) raw="null";
        if(kind.equals("tombstone")) raw=null;
        if(kind.equals("duplicateKey")) raw=raw.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1");
        receiver.receive("test",0,++coordinate,key,raw);
        assertDiagnostic("VALIDATION_ERROR"); assertThat(count("inventory")).isZero(); assertThat(count("stock_receipts")).isZero();
        assertThat(jdbc.queryForObject("SELECT raw_message FROM incoming_goods_diagnostics WHERE topic='test' AND kafka_offset=?",String.class,coordinate)).isEqualTo(raw);
    }

    /** A real consumer storage failure prevents offset advancement until its poison-message diagnostic has been committed. */
    @Test
    void consumerOffsetWaitsForDiagnosticCommit() throws Exception {
        failingTrigger("incoming_goods_diagnostics","fail_diagnostic","INSERT");
        var sent=kafka.send("warehouse.goods-posted",store,"{").get(10,TimeUnit.SECONDS).getRecordMetadata();
        verify(receiver,timeout(15000).atLeastOnce()).receive(eq(sent.topic()),eq(sent.partition()),eq(sent.offset()),eq(store),eq("{"));
        TopicPartition partition=new TopicPartition(sent.topic(),sent.partition());
        try(AdminClient admin=AdminClient.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            var offsets=admin.listConsumerGroupOffsets("store-goods-v1").partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
            assertThat(offsets.get(partition)==null?0L:offsets.get(partition).offset()).isLessThanOrEqualTo(sent.offset());
            jdbc.execute("DROP TRIGGER fail_diagnostic ON incoming_goods_diagnostics");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM incoming_goods_diagnostics WHERE topic=? AND partition_id=? AND kafka_offset=?",
                        Integer.class,sent.topic(),sent.partition(),sent.offset())).isEqualTo(1);
                assertThat(admin.listConsumerGroupOffsets("store-goods-v1").partitionsToOffsetAndMetadata().get(3,TimeUnit.SECONDS).get(partition).offset()).isGreaterThan(sent.offset());
            });
        }
    }

    /** Catalogue's maximum valid size survives a multi-megabyte Kafka event; another new SKU is atomically rejected. */
    @Test
    void catalogLimitIsEnforcedWithoutTruncatingKafkaMessage() throws Exception {
        ObjectNode input=event(1,1,"100.00","0.20"); ArrayNode items=(ArrayNode)input.path("payload").path("items");
        ObjectNode template=((ObjectNode)items.get(0)).deepCopy(); template.put("description","d".repeat(2000)); items.removeAll();
        for(int i=0;i<1000;i++) items.add(template.deepCopy().put("lineId","L-"+i).put("productId","P-"+i));
        kafka.send("warehouse.goods-posted",store,codec.json(input)).get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count("inventory")).isEqualTo(1000));
        assertThat(carts.catalog(store).items()).hasSize(1000);
        ObjectNode overflow=event(2,1,"100.00","0.20");
        ((ObjectNode)overflow.path("payload").path("items").get(0)).put("productId","P-extra"); receive(overflow);
        assertDiagnostic("VALIDATION_ERROR"); assertThat(count("stock_receipts")).isEqualTo(1); assertThat(count("inventory")).isEqualTo(1000);
    }

    /** Cart sum overflow rejects its mutation and rolls back the tentative line/version rather than sending out-of-contract money. */
    @Test
    void aggregateMoneyLimitRejectsMutationAtomically() {
        ObjectNode input=event(1,Integer.MAX_VALUE,"10000000000000000000000000.00","0.0");
        for(int i=2;i<=47;i++) addLine(input,"L-"+i,"P-"+i);
        receive(input); UUID cart=carts.create(store).cartId(); List<Stock> stocks=carts.catalog(store).items();
        for(int i=0;i<46;i++) jdbc.update("INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) VALUES(?,?,?,?)",store,cart,stocks.get(i).stockItemId(),Integer.MAX_VALUE);
        jdbc.update("UPDATE carts SET version=46 WHERE cart_id=?",cart);
        assertThat(carts.get(store,cart).items()).hasSize(46);
        assertError(put(cart,stocks.get(46).stockItemId(),Integer.MAX_VALUE,46),400,"VALIDATION_ERROR");
        assertThat(carts.get(store,cart).version()).isEqualTo(46); assertThat(carts.get(store,cart).items()).hasSize(46);
    }

    /** Concurrent full replacements and GETs always expose matching composition/version from one database snapshot. */
    @Test
    void concurrentCartReadsNeverMixHeaderAndComposition() throws Exception {
        receive(event(1,100,"100.00","0.20"));
        UUID stock=stock().stockItemId(); UUID cart=carts.create(store).cartId();
        CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<?> writer=pool.submit(() -> {
                awaitStart(start);
                for(int quantity=1;quantity<=50;quantity++)
                    assertThat(put(cart,stock,quantity,quantity-1).getStatusCode().value()).isEqualTo(200);
            });
            Future<?> reader=pool.submit(() -> {
                awaitStart(start);
                for(int attempt=0;attempt<100;attempt++) {
                    ResponseEntity<JsonNode> response=http.getForEntity("/stores/"+store+"/carts/"+cart,JsonNode.class);
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    JsonNode view=response.getBody(); long version=view.path("version").asLong();
                    if(version==0) assertThat(view.path("items").size()).isZero();
                    else {
                        assertThat(view.path("items").size()).isEqualTo(1);
                        assertThat(view.path("items").get(0).path("quantity").asLong()).isEqualTo(version);
                    }
                }
            });
            start.countDown(); writer.get(20,TimeUnit.SECONDS); reader.get(20,TimeUnit.SECONDS);
        }
        assertThat(carts.get(store,cart).version()).isEqualTo(50);
    }

    /** Unknown/malformed scopes and UUIDs are client errors, while obsolete purchase/auth/raw-product routes remain unavailable. */
    @Test
    void removedLegacyRoutesAndInvalidPathsCannotBypassNewModel() {
        for(String path:List.of("/api/v1/products","/api/cart","/tariffs?all=true"))
            assertError(http.getForEntity(path,JsonNode.class),404,"NOT_FOUND");
        for(String path:List.of("/order","/api/v1/sendToKafka","/api/cart","/api/cart/decrement","/api/v1/auth"))
            assertThat(request(HttpMethod.POST,path,"{}").getStatusCode().value()).isIn(404,405);
        assertThat(request(HttpMethod.DELETE,"/api/cart/clear",null).getStatusCode().value()).isIn(404,405);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product",Integer.class)).isZero();
        assertError(http.getForEntity("/stores/UNKNOWN/catalog",JsonNode.class),404,"NOT_FOUND");
        assertError(http.getForEntity("/stores/"+store+"/carts/1-1-1-1-1",JsonNode.class),400,"VALIDATION_ERROR");
        assertError(request(HttpMethod.POST,"/stores/"+store+"/carts","{}"),400,"VALIDATION_ERROR");
        var created=carts.create(store);
        assertError(request(HttpMethod.DELETE,"/stores/"+store+"/carts/"+created.cartId()+"/items/"+UUID.randomUUID(),null),400,"VALIDATION_ERROR");
    }

    /** Builds a mathematically consistent independent GoodsPosted event; each invocation receives a new delivery UUID. */
    private ObjectNode event(long sequence,int quantity,String purchase,String rate) {
        String sale=new BigDecimal(purchase).multiply(BigDecimal.ONE.add(new BigDecimal(rate))).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();
        return (ObjectNode)codec.read("""
                {"eventId":"%s","eventType":"GoodsPosted","schemaVersion":1,"occurredAt":"2026-10-03T10:00:02Z","storeId":"%s",
                "payload":{"deliveryId":"%s","deliverySequence":%d,"receivedAt":"2026-10-03T10:00:01Z","postedAt":"2026-10-03T10:00:02Z",
                "items":[{"lineId":"L-1","productId":"P-1","productType":"NON_FOOD","shortName":"Soap","description":"Sample",
                "quantity":%d,"purchasePrice":"%s","currency":"RUB","markupRate":"%s","tariffRuleId":"b3000000-0000-4000-8000-000000000001",
                "tariffVersion":1,"salePrice":"%s"}]}}
                """.formatted(UUID.randomUUID(),store,"D-"+UUID.randomUUID(),sequence,quantity,purchase,rate,sale));
    }
    /** Adds a separate product/line retaining the scenario's exact financial values. */
    private void addLine(ObjectNode event,String lineId,String productId) {
        ArrayNode items=(ArrayNode)event.path("payload").path("items");
        items.add(((ObjectNode)items.get(0)).deepCopy().put("lineId",lineId).put("productId",productId));
    }
    /** Executes real receipt transactions directly for focused database invariants, with independent synthetic coordinates. */
    private void receive(JsonNode input) { receiver.receive("test",0,++coordinate,store,codec.json(input)); }
    /** Reads the first SKU of this independent store; scenarios requiring multiple lines assert the entire catalogue separately. */
    private Stock stock() { return carts.catalog(store).items().getFirst(); }
    /** Counts only this scenario's store-owned data. Table names are fixed test constants, never user input. */
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE store_id=?",Integer.class,store); }
    /** Sends an exact PUT wire document to the real HTTP API. */
    private ResponseEntity<JsonNode> put(UUID cart,UUID stock,int quantity,long version) {
        return request(HttpMethod.PUT,"/stores/"+store+"/carts/"+cart+"/items/"+stock,putBody(quantity,version));
    }
    /** Sends versioned DELETE using the protocol's query parameter. */
    private ResponseEntity<JsonNode> delete(UUID cart,UUID stock,long version) {
        return request(HttpMethod.DELETE,"/stores/"+store+"/carts/"+cart+"/items/"+stock+"?expectedCartVersion="+version,null);
    }
    /** Constructs a minimal full-replacement body without prices, total or ownership fields. */
    private String putBody(int quantity,long version) { return "{\"quantity\":"+quantity+",\"expectedCartVersion\":"+version+"}"; }
    /** Uses real JSON HTTP parsing, allowing malformed bodies to reach controller validation. */
    private ResponseEntity<JsonNode> request(HttpMethod method,String path,String raw) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path,method,new HttpEntity<>(raw,headers),JsonNode.class);
    }
    /** Checks named contract status and UTC timestamp without accepting leaked SQL or credentials. */
    private void assertError(ResponseEntity<JsonNode> response,int status,String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().path("timestamp").asText()).endsWith("Z");
        assertThat(response.getBody().toString()).doesNotContain("password","jdbc:","SELECT ","stackTrace");
    }
    /** Verifies the last synthetic Kafka coordinate has a persisted safe diagnostic. */
    private void assertDiagnostic(String code) {
        assertThat(jdbc.queryForObject("SELECT code FROM incoming_goods_diagnostics WHERE topic='test' AND kafka_offset=?",String.class,coordinate)).isEqualTo(code);
    }
    /** Installs an actual SQL failure boundary inside this test container, never an application crash-control endpoint. */
    private void failingTrigger(String table,String name,String operation) {
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_stock_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE "+operation+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION test_fail_stock_write()");
    }
    /** Starts competing operations from one barrier and preserves interruption as a real test failure. */
    private void awaitStart(CountDownLatch start) {
        try { if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("Competition did not start"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
