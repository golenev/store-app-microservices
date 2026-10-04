package com.shop.warehouse;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.shop.warehouse.delivery.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.shop.warehouse.delivery.DeliveryModels.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** Full HTTP/Kafka/PostgreSQL integration with explicit worker ticks for deterministic failure-window testing. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureWireMock(port=0)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"warehouse.listener.enabled=true", "tariffs.base-url=http://localhost:${wiremock.server.port}"})
class DeliveryIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer broker = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryCodec codec;
    @SpyBean DeliveryStore store;
    @Autowired TariffClient tariffs;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired TestRestTemplate http;
    private DeliveryWorkers workers;
    private String delivery;
    private long coordinate;

    /** Supplies fresh real databases/broker to Spring, without touching any user-owned environment. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers);
    }

    /** Uses independent delivery identifiers and a safe exact quote; scheduled workers stay disabled in this suite. */
    @BeforeEach
    void setup() {
        reset(store);
        com.github.tomakehurst.wiremock.client.WireMock.reset();
        delivery = "D-" + UUID.randomUUID();
        coordinate = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        workers = new DeliveryWorkers(store, tariffs, kafka);
        quote("0.20", 1);
    }

    /** Removes this scenario's persisted data and any test-only SQL fault injection, leaving fixtures intact. */
    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_outbox ON warehouse_outbox");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_diagnostic ON delivery_diagnostics");
        jdbc.update("DELETE FROM warehouse_outbox WHERE delivery_id=?", delivery);
        jdbc.update("DELETE FROM received_events WHERE delivery_id=?", delivery);
        jdbc.update("DELETE FROM delivery_items WHERE delivery_id=?", delivery);
        jdbc.update("DELETE FROM deliveries WHERE delivery_id=?", delivery);
        reset(store);
    }

    /** A Kafka-acknowledged technical publish reaches persisted WAITING, then one posted result and one real GoodsPosted. */
    @Test
    void technicalPublishReachesReceptionPricingAndKafka() throws Exception {
        try (KafkaConsumer<String,String> consumer = goodsConsumer()) {
            JsonNode event = event();
            var response = publish(codec.json(event));
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody().path("publicationStatus").asText()).isEqualTo("PUBLISHED");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count("deliveries")).isEqualTo(1));
            View waiting = store.view("S-1", delivery);
            assertThat(waiting.state()).isEqualTo("WAITING_PRICING");
            workers.priceOne();
            View posted = store.view("S-1", delivery);
            assertThat(posted.state()).isEqualTo("POSTED");
            assertThat(posted.items().getFirst().salePrice()).isEqualTo("120.00");
            assertThat(posted.receivedAt()).isEqualTo(waiting.receivedAt());
            assertThat(posted.deliverySequence()).isEqualTo(waiting.deliverySequence());
            assertThat(count("warehouse_outbox")).isEqualTo(1);
            workers.sendOne();
            ConsumerRecord<String,String> record = goods(consumer);
            GoodsEvent goods = codec.restore(record.value(), GoodsEvent.class);
            assertThat(record.key()).isEqualTo("S-1");
            assertThat(goods.payload().deliveryId()).isEqualTo(delivery);
            assertThat(goods.payload().postedAt()).isEqualTo(goods.occurredAt()).isEqualTo(posted.postedAt());
            assertThat(goods.payload().items().getFirst().salePrice()).isEqualTo("120.00");
            assertThat(jdbc.queryForObject("SELECT publication_status FROM warehouse_outbox WHERE delivery_id=?", String.class, delivery))
                    .isEqualTo("PUBLISHED");
        }
    }

    /** A new transport envelope and reversed item/property order preserve the normalized business identity. */
    @Test
    void reorderedDeliveryWithNewEventDoesNotCreateAnotherAcceptance() {
        ObjectNode input = event();
        ObjectNode second = ((ObjectNode) input.path("payload").path("items").get(0)).deepCopy();
        second.put("lineId", "L-2").put("productId", "P-2");
        ((ArrayNode) input.path("payload").path("items")).add(second);
        receive(input);
        View original = store.view("S-1", delivery);
        ObjectNode duplicate = input.deepCopy();
        duplicate.put("eventId", UUID.randomUUID().toString()).put("occurredAt", "2026-10-04T10:00:00Z");
        ArrayNode reversed = ((ObjectNode) duplicate.path("payload")).putArray("items");
        reversed.add(second).add(input.path("payload").path("items").get(0));
        receive(duplicate);
        receive(input);
        assertThat(count("deliveries")).isEqualTo(1);
        assertThat(count("received_events")).isEqualTo(2);
        assertThat(store.view("S-1", delivery)).isEqualTo(original);
        workers.priceOne();
        receive(duplicate);
        assertThat(count("warehouse_outbox")).isEqualTo(1);
    }

    /** Changed business fields under a reused delivery identity become persisted conflicts and never replace the accepted input. */
    @ParameterizedTest
    @ValueSource(strings={"quantity", "purchasePrice", "shortName", "description", "productType", "productId", "lineId"})
    void changedDeliveryContentIsDiagnosed(String field) {
        ObjectNode input = event();
        receive(input);
        ObjectNode changed = input.deepCopy();
        changed.put("eventId", UUID.randomUUID().toString());
        ObjectNode line = (ObjectNode) changed.path("payload").path("items").get(0);
        if (field.equals("quantity")) line.put(field, 11);
        else line.put(field, field.equals("purchasePrice") ? "101.00" : field.equals("productType") ? "FOOD" : "Changed");
        receive(changed);
        assertThat(jdbc.queryForObject("SELECT code FROM delivery_diagnostics WHERE kafka_offset=?", String.class, coordinate))
                .isEqualTo("DELIVERY_CONTENT_CONFLICT");
        assertThat(store.view("S-1", delivery).items().getFirst().purchasePrice()).isEqualTo("100.00");
        assertThat(count("received_events")).isEqualTo(1);
    }

    /** A reused event UUID cannot switch store or business content even when the second envelope otherwise validates. */
    @Test
    void eventIdentifierCannotMoveBetweenStores() {
        ObjectNode input = event();
        receive(input);
        input.put("storeId", "S-2");
        store.receive("test", 0, coordinate, "S-2", codec.json(input));
        assertThat(count("deliveries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT code FROM delivery_diagnostics WHERE kafka_offset=?", String.class, coordinate))
                .isEqualTo("DELIVERY_CONTENT_CONFLICT");
    }

    /** Concurrent identical accepts allocate one immutable sequence and never hit a leaking uniqueness exception. */
    @Test
    void concurrentReceptionHasOneDeliveryAndSequence() throws Exception {
        String raw = codec.json(event());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> { awaitStart(start); store.receive("test",0,coordinate,"S-1",raw); });
            Future<?> second = pool.submit(() -> { awaitStart(start); store.receive("test",0,coordinate+1,"S-1",raw); });
            start.countDown(); first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
        }
        assertThat(count("deliveries")).isEqualTo(1);
        assertThat(count("received_events")).isEqualTo(1);
    }

    /** Parsed invalid payload becomes REJECTED with original data and zero item rows; automatic pricing ignores it. */
    @ParameterizedTest
    @ValueSource(strings={"zeroQuantity", "overflowQuantity", "decimalQuantity", "numericPrice", "zeroPrice", "badCurrency",
            "unknownType", "blankName", "missingDescription", "duplicateProduct", "duplicateLine", "unknownField", "emptyItems"})
    void invalidPayloadIsPersistedRejected(String kind) {
        ObjectNode input = event();
        ObjectNode line = (ObjectNode) input.path("payload").path("items").get(0);
        ArrayNode items = (ArrayNode) input.path("payload").path("items");
        switch (kind) {
            case "zeroQuantity" -> line.put("quantity",0);
            case "overflowQuantity" -> line.put("quantity",2147483648L);
            case "decimalQuantity" -> line.put("quantity",1.5);
            case "numericPrice" -> line.put("purchasePrice",100);
            case "zeroPrice" -> line.put("purchasePrice","0.00");
            case "badCurrency" -> line.put("currency","USD");
            case "unknownType" -> line.put("productType","ANY");
            case "blankName" -> line.put("shortName"," ");
            case "missingDescription" -> line.remove("description");
            case "duplicateProduct" -> { ObjectNode other=line.deepCopy(); other.put("lineId","L-2"); items.add(other); }
            case "duplicateLine" -> { ObjectNode other=line.deepCopy(); other.put("productId","P-2"); items.add(other); }
            case "unknownField" -> line.put("unexpected",true);
            case "emptyItems" -> items.removeAll();
            default -> throw new AssertionError(kind);
        }
        receive(input);
        View rejected = store.view("S-1",delivery);
        assertThat(rejected.state()).isEqualTo("REJECTED");
        assertThat(rejected.rejectedPayload()).isEqualTo(input.get("payload"));
        assertThat(rejected.items()).isEmpty();
        workers.priceOne();
        assertThat(count("warehouse_outbox")).isZero();
        assertThat(rejected.attemptCount()).isZero();
        assertThat(retry().getStatusCode().value()).isEqualTo(409);
    }

    /** Invalid envelopes are durable Kafka-coordinate diagnostics, not fabricated REJECTED deliveries. */
    @ParameterizedTest
    @ValueSource(strings={"broken", "duplicateKey", "version", "wrappedVersion", "eventType", "uuid", "nonUtc", "unknownEnvelope", "longField", "trailing", "empty", "null", "tombstone"})
    void invalidEnvelopeIsDiagnosed(String kind) {
        ObjectNode input=event();
        String raw;
        switch(kind) {
            case "version" -> input.put("schemaVersion",2);
            case "wrappedVersion" -> input.set("schemaVersion",new BigIntegerNode(new java.math.BigInteger("18446744073709551617")));
            case "eventType" -> input.put("eventType","GoodsPosted");
            case "uuid" -> input.put("eventId","1-1-1-1-1");
            case "nonUtc" -> input.put("occurredAt","2026-10-03T10:00:00+00:00");
            case "unknownEnvelope" -> input.put("extra",true);
            case "longField" -> input.put("x".repeat(2000),true);
            default -> { }
        }
        raw=codec.json(input);
        if(kind.equals("broken")) raw="{";
        if(kind.equals("duplicateKey")) raw=raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
        if(kind.equals("trailing")) raw += " {}";
        if(kind.equals("empty")) raw=" ";
        if(kind.equals("null")) raw="null";
        if(kind.equals("tombstone")) raw=null;
        store.receive("test",0,coordinate,"S-1",raw);
        store.receive("test",0,coordinate,"S-1",raw);
        assertThat(count("deliveries")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_diagnostics WHERE kafka_offset=?",Integer.class,coordinate)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT raw_message FROM delivery_diagnostics WHERE kafka_offset=?",String.class,coordinate)).isEqualTo(raw);
    }

    /** Wrong Kafka routing and unknown stores are diagnosed without allocating a delivery or losing coordinates. */
    @Test
    void routingAndUnknownStoreAreDiagnosed() {
        ObjectNode input=event();
        store.receive("test",0,coordinate,"S-2",codec.json(input));
        input.put("storeId","UNKNOWN");
        store.receive("test",0,coordinate+1,"UNKNOWN",codec.json(input));
        assertThat(count("deliveries")).isZero();
        assertThat(jdbc.queryForList("SELECT code FROM delivery_diagnostics WHERE kafka_offset IN (?,?) ORDER BY kafka_offset",String.class,coordinate,coordinate+1))
                .containsExactly("VALIDATION_ERROR","NOT_FOUND");
    }

    /** Lower-level storage failure rolls back diagnostic acceptance and propagates to the consumer retry path. */
    @Test
    void diagnosticStorageFailureDoesNotReturnSuccess() {
        failingTrigger("delivery_diagnostics","fail_diagnostic");
        assertThatThrownBy(() -> store.receive("test",0,coordinate,"S-1","{")) .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_diagnostics WHERE kafka_offset=?",Integer.class,coordinate)).isZero();
    }

    /** Actual offset remains uncommitted while diagnostics fail; after recovery, wait independently for durable rows and asynchronous offset visibility. */
    @Test
    void kafkaOffsetWaitsForDurableDiagnostic() throws Exception {
        failingTrigger("delivery_diagnostics","fail_diagnostic");
        var sent=kafka.send("logistics.deliveries","S-1","{").get(10,TimeUnit.SECONDS).getRecordMetadata();
        org.mockito.Mockito.verify(store,timeout(15000).atLeastOnce()).receive(eq(sent.topic()),eq(sent.partition()),eq(sent.offset()),eq("S-1"),eq("{"));
        TopicPartition partition=new TopicPartition(sent.topic(),sent.partition());
        try(AdminClient admin=AdminClient.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_diagnostics WHERE topic=? AND partition_id=? AND kafka_offset=?",
                            Integer.class,sent.topic(),sent.partition(),sent.offset())).isZero());
            var offsets=admin.listConsumerGroupOffsets("warehouse-deliveries-v1").partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
            assertThat(offsets.get(partition)==null ? 0L : offsets.get(partition).offset()).isLessThanOrEqualTo(sent.offset());
            jdbc.execute("DROP TRIGGER fail_diagnostic ON delivery_diagnostics");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_diagnostics WHERE topic=? AND partition_id=? AND kafka_offset=?",
                        Integer.class,sent.topic(),sent.partition(),sent.offset())).isEqualTo(1);
                var committed=admin.listConsumerGroupOffsets("warehouse-deliveries-v1").partitionsToOffsetAndMetadata()
                        .get(3,TimeUnit.SECONDS).get(partition);
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isGreaterThan(sent.offset());
            });
        }
    }

    /** A missing tariff saves waiting/backoff; subsequent success keeps sequence/time and posts once without partial fields. */
    @Test
    void missingRuleCanRecoverWithoutChangingAcceptance() {
        stubFor(get(urlPathEqualTo("/tariffs/quote")).willReturn(aResponse().withStatus(404)));
        receive(event());
        View original=store.view("S-1",delivery);
        workers.priceOne();
        View waiting=store.view("S-1",delivery);
        assertThat(waiting.state()).isEqualTo("WAITING_PRICING");
        assertThat(waiting.lastError().code()).isEqualTo("TARIFF_NOT_FOUND");
        assertThat(waiting.attemptCount()).isEqualTo(1);
        assertThat(waiting.nextAttemptAt()).isAfter(waiting.receivedAt());
        quote("0.30",2);
        due();
        workers.priceOne();
        View posted=store.view("S-1",delivery);
        assertThat(posted.state()).isEqualTo("POSTED");
        assertThat(posted.receivedAt()).isEqualTo(original.receivedAt());
        assertThat(posted.deliverySequence()).isEqualTo(original.deliverySequence());
        assertThat(posted.items().getFirst().salePrice()).isEqualTo("130.00");
        assertThat(posted.items().getFirst().tariffVersion()).isEqualTo(2);
    }

    /** Timeout/ambiguity/bad upstream responses remain retryable and never write financial fields or outbox. */
    @ParameterizedTest
    @ValueSource(strings={"ambiguous","outage","timeout","badJson","negativeRate","shortUuid","numericRate","zeroVersion","unknownField"})
    void tariffFailureNeverPartiallyPosts(String kind) {
        String result="{\"markupRate\":\"0.20\",\"tariffRuleId\":\"b3000000-0000-4000-8000-000000000001\",\"tariffVersion\":1}";
        var response=aResponse().withHeader("Content-Type","application/json");
        switch(kind) {
            case "ambiguous" -> response.withStatus(409);
            case "outage" -> response.withStatus(503);
            case "timeout" -> response.withFixedDelay(3000).withBody(result);
            case "badJson" -> response.withBody("{");
            case "negativeRate" -> response.withBody(result.replace("0.20","-0.20"));
            case "shortUuid" -> response.withBody(result.replace("b3000000-0000-4000-8000-000000000001","1-1-1-1-1"));
            case "numericRate" -> response.withBody(result.replace("\"0.20\"","0.20"));
            case "zeroVersion" -> response.withBody(result.replace("\"tariffVersion\":1","\"tariffVersion\":0"));
            case "unknownField" -> response.withBody(result.replace("}",",\"extra\":1}"));
            default -> throw new AssertionError(kind);
        }
        stubFor(get(urlPathEqualTo("/tariffs/quote")).willReturn(response));
        receive(event()); workers.priceOne();
        View view=store.view("S-1",delivery);
        assertThat(view.state()).isEqualTo("WAITING_PRICING");
        assertThat(view.lastError().code()).isEqualTo(kind.equals("ambiguous") ? "TARIFF_AMBIGUOUS" : "DEPENDENCY_UNAVAILABLE");
        assertThat(view.items().getFirst().salePrice()).isNull();
        assertThat(count("warehouse_outbox")).isZero();
    }

    /** A failing later line prevents persistence of an earlier successful quote, preserving whole-delivery atomicity. */
    @Test
    void secondLineFailureDoesNotPersistFirstLinePricing() {
        ObjectNode input=event();
        ObjectNode second=((ObjectNode)input.path("payload").path("items").get(0)).deepCopy();
        second.put("lineId","L-2").put("productId","P-2").put("purchasePrice","200.00");
        ((ArrayNode)input.path("payload").path("items")).add(second);
        stubFor(get(urlPathEqualTo("/tariffs/quote")).withQueryParam("purchasePrice",equalTo("200.00")).atPriority(1)
                .willReturn(aResponse().withStatus(503)));
        receive(input); workers.priceOne();
        assertThat(store.view("S-1",delivery).items()).allSatisfy(line -> assertThat(line.salePrice()).isNull());
        assertThat(count("warehouse_outbox")).isZero();
    }

    /** HALF_UP handles a half kopeck exactly and preserves all six rate decimals. */
    @Test
    void salePriceUsesExactHalfUp() {
        ObjectNode input=event(); ((ObjectNode)input.path("payload").path("items").get(0)).put("purchasePrice","0.05");
        quote("0.10",1); receive(input); workers.priceOne();
        assertThat(store.view("S-1",delivery).items().getFirst().salePrice()).isEqualTo("0.06");
        assertThat(store.view("S-1",delivery).items().getFirst().markupRate()).isEqualTo("0.100000");
    }

    /** A mathematically valid input whose sale price exceeds the wire/database limit remains diagnosed waiting, never truncated. */
    @Test
    void salePriceOverflowIsNotSilentlyRoundedOrStored() {
        ObjectNode input=event(); ((ObjectNode)input.path("payload").path("items").get(0)).put("purchasePrice","99999999999999999999999999.99");
        quote("999.999999",1); receive(input); workers.priceOne();
        assertThat(store.view("S-1",delivery).lastError().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(count("warehouse_outbox")).isZero();
    }

    /** An SQL failure between line updates and outbox insert rolls back the entire post transaction. */
    @Test
    void outboxFailureRollsBackPostedAndLinePrices() {
        receive(event()); failingTrigger("warehouse_outbox","fail_outbox");
        assertThatThrownBy(workers::priceOne).isInstanceOf(DataAccessException.class);
        View view=store.view("S-1",delivery);
        assertThat(view.state()).isEqualTo("WAITING_PRICING");
        assertThat(view.items().getFirst().salePrice()).isNull();
        assertThat(count("warehouse_outbox")).isZero();
        jdbc.execute("DROP TRIGGER fail_outbox ON warehouse_outbox");
        expirePricing(); workers.priceOne();
        assertThat(store.view("S-1",delivery).state()).isEqualTo("POSTED");
    }

    /** Active lease prevents manual retry from launching another calculation; reclaimed tokens fence every old write. */
    @Test
    void manualRetryAndReclaimedWorkerCannotPostTwice() {
        receive(event()); PricingWork old=store.claimPricing().orElseThrow();
        assertThat(retry().getStatusCode().value()).isEqualTo(202);
        assertThat(store.claimPricing()).isEmpty();
        expirePricing(); PricingWork current=store.claimPricing().orElseThrow();
        List<Line> priced=current.items().stream().map(line -> tariffs.price(line,current.cityId())).toList();
        assertThat(store.renew(old)).isFalse();
        assertThat(store.post(old,priced)).isFalse();
        store.failedPricing(old,new Failure("DEPENDENCY_UNAVAILABLE","Old attempt"));
        assertThat(store.post(current,priced)).isTrue();
        assertThat(store.post(current,priced)).isFalse();
        assertThat(count("warehouse_outbox")).isEqualTo(1);
        assertThat(retry().getStatusCode().value()).isEqualTo(409);
    }

    /** Commit-before-publish leaves a durable pending event whose retry never calls TARIFFS or changes its stored payload. */
    @Test
    void pendingEventIsIndependentOfPricingAfterCommit() {
        receive(event()); workers.priceOne();
        String original=jdbc.queryForObject("SELECT payload FROM warehouse_outbox WHERE delivery_id=?",String.class,delivery);
        com.github.tomakehurst.wiremock.client.WireMock.reset();
        workers.priceOne(); workers.sendOne();
        assertThat(jdbc.queryForObject("SELECT payload FROM warehouse_outbox WHERE delivery_id=?",String.class,delivery)).isEqualTo(original);
        verify(0,getRequestedFor(urlPathEqualTo("/tariffs/quote")));
    }

    /** Losing the DB acknowledgement after a real Kafka send yields two identical physical records and one logical outbox. */
    @Test
    void lostPublishedMarkReplaysSameEventAndPayload() throws Exception {
        try(KafkaConsumer<String,String> consumer=goodsConsumer()) {
            receive(event()); workers.priceOne();
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("Test lost mark")).when(store).published(any());
            assertThatThrownBy(workers::sendOne).isInstanceOf(DataAccessException.class);
            ConsumerRecord<String,String> first=goods(consumer);
            reset(store);
            jdbc.update("UPDATE warehouse_outbox SET lease_until=now()-interval '1 second' WHERE delivery_id=?",delivery);
            workers.sendOne();
            ConsumerRecord<String,String> second=goods(consumer);
            assertThat(second.value()).isEqualTo(first.value());
            assertThat(second.key()).isEqualTo(first.key());
            assertThat(count("warehouse_outbox")).isEqualTo(1);
        }
    }

    /** Unavailable Kafka preserves PENDING; recovery publishes the same event without repeating pricing. */
    @Test
    void kafkaOutageKeepsOutboxPendingThenRecovers() {
        receive(event()); workers.priceOne();
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
        try {
            workers.sendOne();
            assertThat(jdbc.queryForObject("SELECT publication_status FROM warehouse_outbox WHERE delivery_id=?",String.class,delivery)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM warehouse_outbox WHERE delivery_id=?",Integer.class,delivery)).isEqualTo(1);
        } finally { broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            jdbc.update("UPDATE warehouse_outbox SET next_attempt_at=now()-interval '1 second' WHERE delivery_id=?",delivery);
            workers.sendOne();
            assertThat(jdbc.queryForObject("SELECT publication_status FROM warehouse_outbox WHERE delivery_id=?",String.class,delivery)).isEqualTo("PUBLISHED");
        });
    }

    /** Store-specific URLs cannot expose another store's delivery and failures retain the canonical safe HTTP shape. */
    @Test
    void statusApiValidatesScopeAndErrors() {
        receive(event());
        var ok=http.getForEntity("/stores/S-1/deliveries/"+delivery,JsonNode.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().has("postedAt")).isFalse();
        var missing=http.getForEntity("/stores/S-2/deliveries/"+delivery,JsonNode.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(missing.getBody().path("timestamp").asText()).endsWith("Z");
        assertThat(publish("{").getStatusCode().value()).isEqualTo(400);
    }

    /** Two simultaneous worker claims have exactly one owner, independent of HTTP request concurrency. */
    @Test
    void concurrentPricingClaimsHaveOneOwner() throws Exception {
        receive(event());
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            var first=pool.submit(() -> { awaitStart(start); return store.claimPricing(); });
            var second=pool.submit(() -> { awaitStart(start); return store.claimPricing(); });
            start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
    }

    /** The maximum valid collection survives real Kafka above its default 1 MB ceiling; oversize collections are rejected before item writes. */
    @Test
    void thousandLineDeliveryTraversesKafkaWithoutTruncation() {
        ObjectNode input=event();
        ArrayNode items=(ArrayNode)input.path("payload").path("items");
        ObjectNode template=((ObjectNode)items.get(0)).deepCopy();
        template.put("description","d".repeat(2000));
        items.removeAll();
        for(int index=0;index<1000;index++) {
            ObjectNode line=template.deepCopy(); line.put("lineId","L-"+index).put("productId","P-"+index); items.add(line);
        }
        assertThat(publish(codec.json(input)).getStatusCode().value()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count("delivery_items")).isEqualTo(1000));
        assertThat(store.view("S-1",delivery).items()).hasSize(1000);
        items.add(template.deepCopy().put("lineId","L-1000").put("productId","P-1000"));
        assertThat(publish(codec.json(input)).getStatusCode().value()).isEqualTo(400);
        assertThat(count("delivery_items")).isEqualTo(1000);
    }

    /** Terminal sequence exhaustion becomes a durable diagnostic rather than an endless SQL/consumer failure loop. */
    @Test
    void exhaustedStoreSequenceIsDiagnosedWithoutOverflow() {
        long previous=jdbc.queryForObject("SELECT delivery_sequence FROM stores WHERE store_id='S-1'",Long.class);
        jdbc.update("UPDATE stores SET delivery_sequence=9007199254740991 WHERE store_id='S-1'");
        try {
            receive(event());
            assertThat(count("deliveries")).isZero();
            assertThat(jdbc.queryForObject("SELECT code FROM delivery_diagnostics WHERE kafka_offset=?",String.class,coordinate)).isEqualTo("DEPENDENCY_UNAVAILABLE");
        } finally { jdbc.update("UPDATE stores SET delivery_sequence=? WHERE store_id='S-1'",previous); }
    }

    /** Builds one valid independent supplier event; tests mutate only their scenario's document. */
    private ObjectNode event() {
        return (ObjectNode) codec.read("""
                {"eventId":"%s","eventType":"DeliveryReceived","schemaVersion":1,"occurredAt":"2026-10-03T10:00:00Z","storeId":"S-1",
                "payload":{"deliveryId":"%s","items":[{"lineId":"L-1","productId":"P-1","productType":"NON_FOOD","shortName":"Soap",
                "description":"Educational product","quantity":10,"purchasePrice":"100.00","currency":"RUB"}]}}
                """.formatted(UUID.randomUUID(),delivery));
    }

    /** Supplies exact controlled quote HTTP data with independently selectable version and rate. */
    private void quote(String rate,long version) {
        stubFor(get(urlPathEqualTo("/tariffs/quote")).atPriority(5).willReturn(okJson(
                "{\"markupRate\":\""+rate+"\",\"tariffRuleId\":\"b3000000-0000-4000-8000-000000000001\",\"tariffVersion\":"+version+"}")));
    }

    /** Executes the real transactional ingress with unique synthetic coordinates for non-transport scenarios. */
    private void receive(JsonNode event) { store.receive("test",0,++coordinate,"S-1",codec.json(event)); }

    /** Counts only this independent delivery's rows; unrelated previous scenarios cannot satisfy assertions. */
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE delivery_id=?",Integer.class,delivery); }

    /** Calls the actual technical publisher using an application/json document, including malformed-input tests. */
    private ResponseEntity<JsonNode> publish(String raw) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/technical/deliveries",HttpMethod.POST,new HttpEntity<>(raw,headers),JsonNode.class);
    }

    /** Calls the diagnostic retry endpoint without bypassing its state and lease checks. */
    private ResponseEntity<JsonNode> retry() {
        return http.postForEntity("/stores/S-1/deliveries/"+delivery+"/retry-pricing",null,JsonNode.class);
    }

    /** Advances persisted next-attempt eligibility explicitly for deterministic manual-tick tests. */
    private void due() { jdbc.update("UPDATE deliveries SET next_attempt_at=now()-interval '1 second' WHERE delivery_id=?",delivery); }

    /** Simulates elapsed lease/crash recovery in SQL without arbitrary sleeping or changing accepted data. */
    private void expirePricing() { jdbc.update("UPDATE deliveries SET lease_until=now()-interval '1 second',next_attempt_at=now()-interval '1 second' WHERE delivery_id=?",delivery); }

    /** Installs a test-only PostgreSQL failure at an actual write boundary; no production crash-control API exists. */
    private void failingTrigger(String table,String name) {
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION test_fail_write()");
    }

    /** Assigns an independent real Kafka consumer to the current end so earlier tests cannot satisfy this scenario. */
    private KafkaConsumer<String,String> goodsConsumer() {
        KafkaConsumer<String,String> consumer=new KafkaConsumer<>(Map.of("bootstrap.servers",broker.getBootstrapServers(),
                "group.id","goods-test-"+UUID.randomUUID(),"enable.auto.commit",false,
                "key.deserializer","org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer","org.apache.kafka.common.serialization.StringDeserializer"));
        TopicPartition partition=new TopicPartition("warehouse.goods-posted",0);
        consumer.assign(List.of(partition)); consumer.seekToEnd(List.of(partition)); consumer.position(partition);
        return consumer;
    }

    /** Waits with a fixed deadline for this scenario's GoodsPosted, checking delivery identity before returning. */
    private ConsumerRecord<String,String> goods(KafkaConsumer<String,String> consumer) {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline) for(var record:consumer.poll(Duration.ofMillis(200)))
            if(codec.read(record.value()).path("payload").path("deliveryId").asText().equals(delivery)) return record;
        throw new AssertionError("GoodsPosted not received for "+delivery);
    }

    /** Synchronizes competing SQL operations and preserves interruption instead of hiding it in a pool task. */
    private void awaitStart(CountDownLatch start) {
        try { start.await(5,TimeUnit.SECONDS); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
