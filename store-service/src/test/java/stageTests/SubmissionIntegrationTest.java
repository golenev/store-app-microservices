package stageTests;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.store.StoreServiceApplication;
import com.shop.store.shop.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.shop.store.shop.ShopModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/HTTP acceptance and Kafka publication; faults exist only in test SQL/Mockito boundaries. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=StoreServiceApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"store.sender.enabled=false","store.listener.enabled=false"})
class SubmissionIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer broker=new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired CartService carts;
    @Autowired GoodsReceiver receiver;
    @Autowired SubmissionService submissions;
    @Autowired KafkaTemplate<String,String> kafka;
    @SpyBean SubmissionStore operations;
    @SpyBean ShopCodec codec;
    private String store,otherStore;
    private long sequence;

    /** Isolates infrastructure endpoints while keeping production Flyway and transaction handling. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers",broker::getBootstrapServers);
    }
    /** Creates independent store scopes; no fixture inventory or operation keys are shared between tests. */
    @BeforeEach
    void setup() {
        store="T-"+UUID.randomUUID(); otherStore="T-"+UUID.randomUUID(); sequence=0;
        jdbc.update("INSERT INTO store_scopes(store_id) VALUES(?),(?)",store,otherStore);
        clearInvocations(operations,codec);
    }
    /** Clears only this scenario's data in FK order and restores every test-only SQL trigger. */
    @AfterEach
    void cleanup() {
        for(String table:List.of("stock_expenses","store_outbox","carts"))
            jdbc.execute("DROP TRIGGER IF EXISTS test_fail_submission ON "+table);
        for(String scope:List.of(store,otherStore)) {
            for(String table:List.of("store_outbox","stock_expenses","submissions","cart_items","carts","stock_movements","processed_events","inventory","stock_receipts"))
                jdbc.update("DELETE FROM "+table+" WHERE store_id=?",scope);
            jdbc.update("DELETE FROM store_scopes WHERE store_id=?",scope);
        }
    }

    /** A three-unit cart commits exactly one expense/submission/outbox and immutable closure before HTTP 202. */
    @Test
    void acceptanceCommitsStockSnapshotAndOutboxTogether() {
        UUID stock=receive(store,"P-1",10,"120.00"); UUID cart=cart(store,stock,3);
        ResponseEntity<JsonNode> accepted=submit(store,cart,"buy-"+UUID.randomUUID(),1);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        JsonNode result=accepted.getBody(); UUID id=UUID.fromString(result.path("submissionId").asText());
        assertThat(result.path("publicationStatus").asText()).isEqualTo("PENDING");
        assertThat(result.has("publishedAt")).isFalse();
        assertThat(result.path("acceptedAt").asText()).endsWith("Z");
        assertThat(accepted.getHeaders().getLocation().toString()).endsWith("/submissions/"+id);
        assertThat(quantity(stock)).isEqualTo(7); assertThat(count("submissions")).isEqualTo(1);
        assertThat(count("store_outbox")).isEqualTo(1); assertThat(count("stock_expenses")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_expenses WHERE submission_id=?",Integer.class,id)).isEqualTo(3);
        Cart closed=carts.get(store,cart);
        assertThat(closed.state()).isEqualTo("SUBMITTED"); assertThat(closed.version()).isEqualTo(2);
        assertThat(closed.submissionId()).isEqualTo(id); assertThat(closed.totalAmount()).isEqualTo("360.00");
        JsonNode event=event(id);
        assertThat(event.path("eventId").asText()).isEqualTo(result.path("eventId").asText());
        assertThat(event.path("occurredAt").asText()).isEqualTo(result.path("acceptedAt").asText());
        assertThat(event.path("eventType").asText()).isEqualTo("OrderSubmitted");
        assertThat(event.path("payload").path("items").get(0).path("lineTotal").asText()).isEqualTo("360.00");
        assertThat(http.getForEntity("/stores/"+store+"/submissions/"+id,JsonNode.class).getBody()).isEqualTo(result);
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        assertError(http.exchange("/stores/"+store+"/carts/"+cart+"/items/"+stock,HttpMethod.PUT,
                new HttpEntity<>("{\"quantity\":1,\"expectedCartVersion\":2}",headers),JsonNode.class),409,"CART_ALREADY_SUBMITTED");
        assertError(http.exchange("/stores/"+store+"/carts/"+cart+"/items/"+stock+"?expectedCartVersion=2",HttpMethod.DELETE,
                HttpEntity.EMPTY,JsonNode.class),409,"CART_ALREADY_SUBMITTED");
    }

    /** A current-price multi-line cart computes exact HALF_UP-derived decimals with BigDecimal and one expense per SKU. */
    @Test
    void multipleLinesUseServerOwnedExactMoney() {
        UUID first=receive(store,"P-1",5,"0.06"),second=receive(store,"P-2",5,"99999999999999999999999999.99");
        UUID cart=cart(store,first,3); carts.put(store,cart,second,new PutItem(2,1));
        Submission accepted=submissions.submit(store,cart,"decimal",new SubmitInput(2));
        assertThat(carts.get(store,cart).totalAmount()).isEqualTo("200000000000000000000000000.16");
        assertThat(event(accepted.submissionId()).path("payload").path("items").size()).isEqualTo(2);
        assertThat(quantity(first)).isEqualTo(2); assertThat(quantity(second)).isEqualTo(3);
        assertThat(count("stock_expenses")).isEqualTo(2);
    }

    /** Another accepted purchase consumes a cart's requested SKU; submit rechecks it and leaves the losing cart OPEN. */
    @Test
    void depletedStockRejectsSubmitWithoutWrites() {
        UUID stock=receive(store,"P-1",5,"120.00"),first=cart(store,stock,5),second=cart(store,stock,1);
        submissions.submit(store,first,"first",new SubmitInput(1));
        assertError(submit(store,second,"second",1),409,"INSUFFICIENT_STOCK");
        assertThat(quantity(stock)).isZero(); assertThat(count("submissions")).isEqualTo(1);
        assertThat(carts.get(store,second).state()).isEqualTo("OPEN"); assertThat(carts.get(store,second).version()).isEqualTo(1);
    }

    /** Insufficient quantity for any line rejects the entire cart; another available SKU is never partially deducted. */
    @Test
    void oneInsufficientLineRollsBackWholePurchase() {
        UUID first=receive(store,"P-1",5,"120.00"),second=receive(store,"P-2",1,"10.00");
        UUID pending=cart(store,first,3); carts.put(store,pending,second,new PutItem(1,1));
        UUID buyer=cart(store,second,1); submissions.submit(store,buyer,"consume",new SubmitInput(1));
        assertError(submit(store,pending,"blocked",2),409,"INSUFFICIENT_STOCK");
        assertThat(quantity(first)).isEqualTo(5); assertThat(quantity(second)).isZero();
        assertThat(count("submissions")).isEqualTo(1); assertThat(count("stock_expenses")).isEqualTo(1);
        assertThat(carts.get(store,pending).version()).isEqualTo(2); assertThat(carts.get(store,pending).state()).isEqualTo("OPEN");
    }

    /** Concurrent carts competing for the last unit have exactly one HTTP 202 winner and no negative balance. */
    @Test
    void concurrentPurchasesOfLastUnitHaveOneWinner() throws Exception {
        UUID stock=receive(store,"P-1",1,"120.00"),first=cart(store,stock,1),second=cart(store,stock,1);
        CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<JsonNode>> a=pool.submit(() -> { awaitStart(start); return submit(store,first,"A",1); });
            Future<ResponseEntity<JsonNode>> b=pool.submit(() -> { awaitStart(start); return submit(store,second,"B",1); });
            start.countDown(); var left=a.get(15,TimeUnit.SECONDS); var right=b.get(15,TimeUnit.SECONDS);
            assertThat(List.of(left.getStatusCode().value(),right.getStatusCode().value())).containsExactlyInAnyOrder(202,409);
            assertError(left.getStatusCode().value()==409?left:right,409,"INSUFFICIENT_STOCK");
        }
        assertThat(quantity(stock)).isZero(); assertThat(count("submissions")).isEqualTo(1); assertThat(count("stock_expenses")).isEqualTo(1);
    }

    /** Parallel acceptance of opposite cart insertion order locks inventory by UUID, completing both without deadlock. */
    @Test
    void multipleSkuConcurrencyUsesOneStableLockOrder() throws Exception {
        UUID first=receive(store,"P-1",4,"120.00"),second=receive(store,"P-2",4,"10.00");
        UUID a=cart(store,first,1),b=cart(store,second,1);
        carts.put(store,a,second,new PutItem(1,1)); carts.put(store,b,first,new PutItem(1,1));
        CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(() -> { awaitStart(start); return submit(store,a,"A",2); });
            var right=pool.submit(() -> { awaitStart(start); return submit(store,b,"B",2); }); start.countDown();
            assertThat(left.get(15,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(202);
            assertThat(right.get(15,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(202);
        }
        assertThat(quantity(first)).isEqualTo(2); assertThat(quantity(second)).isEqualTo(2); assertThat(count("stock_expenses")).isEqualTo(4);
    }

    /** Concurrent cart replacement and submit cannot both consume the same version; winner determines the one coherent result. */
    @Test
    void cartEditAndSubmitCompeteForTheSameVersion() throws Exception {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,1); CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var purchase=pool.submit(() -> { awaitStart(start); return submit(store,cart,"purchase",1); });
            var edit=pool.submit(() -> {
                awaitStart(start); HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
                return http.exchange("/stores/"+store+"/carts/"+cart+"/items/"+stock,HttpMethod.PUT,
                        new HttpEntity<>("{\"quantity\":2,\"expectedCartVersion\":1}",headers),JsonNode.class);
            }); start.countDown();
            var accepted=purchase.get(15,TimeUnit.SECONDS); var changed=edit.get(15,TimeUnit.SECONDS);
            if(accepted.getStatusCode().value()==202) {
                assertError(changed,409,"CART_ALREADY_SUBMITTED");
                assertThat(quantity(stock)).isEqualTo(4); assertThat(carts.get(store,cart).state()).isEqualTo("SUBMITTED");
            } else {
                assertError(accepted,409,"CART_VERSION_CONFLICT"); assertThat(changed.getStatusCode().value()).isEqualTo(200);
                assertThat(quantity(stock)).isEqualTo(5); assertThat(count("submissions")).isZero();
                assertThat(carts.get(store,cart).items().getFirst().quantity()).isEqualTo(2);
            }
        }
        assertThat(carts.get(store,cart).version()).isEqualTo(2);
    }

    /** Replenishment and acceptance share stock locks: both commit with exact balance and either whole old or whole new price. */
    @Test
    void replenishmentAndSubmitPreserveBalanceAndConsistentPrice() throws Exception {
        UUID stock=receive(store,"P-1",6,"120.00"),cart=cart(store,stock,3); CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var purchase=pool.submit(() -> { awaitStart(start); return submit(store,cart,"buy",1); });
            var delivery=pool.submit(() -> { awaitStart(start); return receive(store,"P-1",10,"144.00"); }); start.countDown();
            assertThat(purchase.get(15,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(202);
            assertThat(delivery.get(15,TimeUnit.SECONDS)).isEqualTo(stock);
        }
        Cart accepted=carts.get(store,cart);
        assertThat(accepted.totalAmount()).isIn("360.00","432.00");
        assertThat(accepted.items().getFirst().lineTotal()).isEqualTo(accepted.totalAmount());
        assertThat(quantity(stock)).isEqualTo(13); assertThat(carts.catalog(store).items().getFirst().unitPrice()).isEqualTo("144.00");
        assertThat(count("stock_expenses")).isEqualTo(1);
    }

    /** Retrying the original key/version after cart closure and publication returns the original operation without new expense. */
    @Test
    void acceptedRequestReplaysBeforeClosedCartAndVersionChecks() {
        UUID stock=receive(store,"P-1",10,"120.00"),cart=cart(store,stock,3);
        var original=submit(store,cart,"repeat",1).getBody(); sender().sendOne();
        var repeated=submit(store,cart,"repeat",1);
        assertThat(repeated.getStatusCode().value()).isEqualTo(202);
        assertThat(repeated.getBody().path("submissionId")).isEqualTo(original.path("submissionId"));
        assertThat(repeated.getBody().path("publicationStatus").asText()).isEqualTo("PUBLISHED");
        assertThat(repeated.getBody().path("publishedAt").asText()).endsWith("Z");
        assertThat(quantity(stock)).isEqualTo(7); assertThat(count("stock_expenses")).isEqualTo(1);
    }

    /** Simultaneous identical requests to one cart return the same submission, event and expense exactly once. */
    @Test
    void concurrentSameKeyAndCartReturnSameOperation() throws Exception {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,3); CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { awaitStart(start); return submit(store,cart,"repeat",1); });
            var b=pool.submit(() -> { awaitStart(start); return submit(store,cart,"repeat",1); }); start.countDown();
            var left=a.get(15,TimeUnit.SECONDS); var right=b.get(15,TimeUnit.SECONDS);
            assertThat(left.getStatusCode().value()).isEqualTo(202); assertThat(right.getStatusCode().value()).isEqualTo(202);
            assertThat(left.getBody()).isEqualTo(right.getBody());
        }
        assertThat(quantity(stock)).isEqualTo(2); assertThat(count("submissions")).isEqualTo(1);
    }

    /** Distinct carts/SKUs race a real store/key UNIQUE; loser rolls back then detects the winner in a new transaction. */
    @Test
    void conflictingConcurrentKeyRecoversAfterActualUniqueRollback() throws Exception {
        UUID first=receive(store,"P-1",1,"120.00"),second=receive(store,"P-2",1,"10.00");
        UUID a=cart(store,first,1),b=cart(store,second,1); CyclicBarrier beforeInsert=new CyclicBarrier(2);
        doAnswer(invocation -> {
            if(invocation.getArgument(0) instanceof Cart snapshot && snapshot.state().equals("SUBMITTED"))
                beforeInsert.await(10,TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(codec).json(any());
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(() -> submit(store,a,"collision",1)); var right=pool.submit(() -> submit(store,b,"collision",1));
            var l=left.get(15,TimeUnit.SECONDS); var r=right.get(15,TimeUnit.SECONDS);
            assertThat(List.of(l.getStatusCode().value(),r.getStatusCode().value())).containsExactlyInAnyOrder(202,409);
            assertError(l.getStatusCode().value()==409?l:r,409,"IDEMPOTENCY_KEY_REUSED");
        }
        verify(operations).replay(eq(store),eq("collision"),anyString());
        assertThat(quantity(first)+quantity(second)).isEqualTo(1);
        assertThat(count("submissions")).isEqualTo(1); assertThat(count("stock_expenses")).isEqualTo(1); assertThat(count("store_outbox")).isEqualTo(1);
    }

    /** Reusing an accepted key with a different cart or expected version is a conflict, never another purchase. */
    @Test
    void acceptedKeyCannotChangeCanonicalRequest() {
        UUID stock=receive(store,"P-1",10,"120.00"),a=cart(store,stock,3),b=cart(store,stock,1);
        submissions.submit(store,a,"key",new SubmitInput(1));
        assertError(submit(store,b,"key",1),409,"IDEMPOTENCY_KEY_REUSED");
        assertError(submit(store,a,"key",2),409,"IDEMPOTENCY_KEY_REUSED");
        assertError(submit(store,a,"new-key",1),409,"CART_ALREADY_SUBMITTED");
        assertThat(quantity(stock)).isEqualTo(7); assertThat(carts.get(store,b).state()).isEqualTo("OPEN");
    }

    /** The same key in two shops produces independent accepted operations and scoped status lookup. */
    @Test
    void keysAndSubmissionLookupsAreStoreScoped() {
        UUID first=receive(store,"P-1",5,"120.00"),second=receive(otherStore,"P-1",5,"10.00");
        UUID a=cart(store,first,1),b=cart(otherStore,second,2);
        Submission left=submissions.submit(store,a,"shared",new SubmitInput(1));
        Submission right=submissions.submit(otherStore,b,"shared",new SubmitInput(1));
        assertThat(left.submissionId()).isNotEqualTo(right.submissionId());
        assertError(http.getForEntity("/stores/"+otherStore+"/submissions/"+left.submissionId(),JsonNode.class),404,"NOT_FOUND");
        assertError(submit(store,b,"foreign",1),404,"NOT_FOUND");
        assertThat(quantity(first)).isEqualTo(4); assertThat(quantity(second)).isEqualTo(3);
    }

    /** Empty, stale and exhausted versions reject acceptance, leaving inventory and operation tables untouched. */
    @Test
    void emptyStaleAndExhaustedCartCannotSubmit() {
        UUID stock=receive(store,"P-1",5,"120.00"),empty=carts.create(store).cartId();
        assertError(submit(store,empty,"empty",0),400,"VALIDATION_ERROR");
        UUID full=cart(store,stock,1); carts.put(store,full,stock,new PutItem(2,1));
        assertError(submit(store,full,"stale",1),409,"CART_VERSION_CONFLICT");
        jdbc.update("UPDATE carts SET version=? WHERE cart_id=?",ShopCodec.MAX_VERSION,full);
        assertError(submit(store,full,"max",ShopCodec.MAX_VERSION),400,"VALIDATION_ERROR");
        assertThat(count("submissions")).isZero(); assertThat(quantity(stock)).isEqualTo(5);
    }

    /** A failed request does not consume its key: replenishment permits an unchanged cart/version to retry successfully. */
    @Test
    void rejectedKeyCanBeRetriedAfterReplenishment() {
        UUID stock=receive(store,"P-1",1,"120.00"),loser=cart(store,stock,1),winner=cart(store,stock,1);
        submissions.submit(store,winner,"winner",new SubmitInput(1));
        assertError(submit(store,loser,"retry",1),409,"INSUFFICIENT_STOCK");
        receive(store,"P-1",1,"144.00");
        assertThat(submit(store,loser,"retry",1).getStatusCode().value()).isEqualTo(202);
        assertThat(carts.get(store,loser).totalAmount()).isEqualTo("144.00"); assertThat(quantity(stock)).isZero();
    }

    /** Repricing before submit determines accepted money; all later metadata/price changes leave closed cart and event snapshots unchanged. */
    @Test
    void submittedSnapshotSurvivesFuturePriceAndNameChanges() {
        UUID stock=receive(store,"P-1",6,"120.00"),cart=cart(store,stock,3);
        receive(store,"P-1",10,"144.00");
        Submission accepted=submissions.submit(store,cart,"snapshot",new SubmitInput(1));
        String immutable=codec.json(event(accepted.submissionId())); Cart closed=carts.get(store,cart);
        assertThat(closed.totalAmount()).isEqualTo("432.00"); assertThat(quantity(stock)).isEqualTo(13);
        receive(store,"P-1",1,"150.00");
        assertThat(carts.get(store,cart)).isEqualTo(closed);
        assertThat(codec.json(event(accepted.submissionId()))).isEqualTo(immutable);
        assertThat(carts.catalog(store).items().getFirst().shortName()).isNotEqualTo(closed.items().getFirst().shortName());
        assertThat(carts.catalog(store).items().getFirst().unitPrice()).isEqualTo("150.00");
    }

    /** Actual SQL failures after deduction/expenses/outbox roll back all purchase writes and allow retry of the original key. */
    @ParameterizedTest
    @ValueSource(strings={"stock_expenses","store_outbox","carts"})
    void storageFailureRollsBackEveryAcceptanceWrite(String table) {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,3);
        failTrigger(table,table.equals("carts")?"UPDATE":"INSERT",null);
        assertError(submit(store,cart,"rollback",1),503,"DEPENDENCY_UNAVAILABLE");
        assertThat(quantity(stock)).isEqualTo(5); assertThat(carts.get(store,cart).state()).isEqualTo("OPEN");
        assertThat(carts.get(store,cart).version()).isEqualTo(1);
        assertThat(count("submissions")).isZero(); assertThat(count("stock_expenses")).isZero(); assertThat(count("store_outbox")).isZero();
        jdbc.execute("DROP TRIGGER test_fail_submission ON "+table);
        assertThat(submit(store,cart,"rollback",1).getStatusCode().value()).isEqualTo(202); assertThat(quantity(stock)).isEqualTo(2);
    }

    /** A saved current-price cart whose total exceeds v1 after repricing is rejected without truncation or partial deductions. */
    @Test
    void submitRejectsMoneyOverflowAfterRepricing() {
        UUID cart=carts.create(store).cartId();
        for(int i=0;i<47;i++) {
            UUID stock=receive(store,"P-"+i,Integer.MAX_VALUE,"1.00");
            carts.put(store,cart,stock,new PutItem(Integer.MAX_VALUE,i));
        }
        // A SQL price fixture isolates amount overflow from ingress's independently tested quantity limit.
        jdbc.update("UPDATE inventory SET unit_price=10000000000000000000000000.00 WHERE store_id=?",store);
        assertError(submit(store,cart,"overflow",47),400,"VALIDATION_ERROR");
        assertThat(count("submissions")).isZero();
        assertThat(jdbc.queryForObject("SELECT min(available_quantity) FROM inventory WHERE store_id=?",Integer.class,store)).isEqualTo(Integer.MAX_VALUE);
        assertThat(jdbc.queryForObject("SELECT version FROM carts WHERE cart_id=?",Long.class,cart)).isEqualTo(47);
    }

    /** Submit's exact JSON protocol rejects missing/extra/duplicate fields, numeric coercion and unsafe versions before any purchase. */
    @ParameterizedTest
    @ValueSource(strings={"{}","null","{","{\"expectedCartVersion\":\"1\"}","{\"expectedCartVersion\":1.0}",
            "{\"expectedCartVersion\":-1}","{\"expectedCartVersion\":9007199254740992}",
            "{\"expectedCartVersion\":1,\"expectedCartVersion\":1}","{\"expectedCartVersion\":1,\"totalAmount\":\"0.01\"}"})
    void invalidSubmitJsonCannotPurchase(String raw) {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,1);
        assertError(rawSubmit(store,cart,"key",raw),400,"VALIDATION_ERROR");
        assertThat(quantity(stock)).isEqualTo(5); assertThat(count("submissions")).isZero();
    }

    /** Missing, whitespace/non-ASCII, overlong and forbidden-character keys reject before accepting any expense. */
    @ParameterizedTest
    @ValueSource(strings={"","space key","ключ","slash/key","oversized"})
    void invalidKeysCannotPurchase(String key) {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,1);
        String wire=key.isEmpty()?null:key.equals("oversized")?"a".repeat(129):key;
        assertError(rawSubmit(store,cart,wire,"{\"expectedCartVersion\":1}"),400,"VALIDATION_ERROR");
        assertThat(count("submissions")).isZero(); assertThat(quantity(stock)).isEqualTo(5);
    }

    /** Real broker publication uses the persisted event/key, and subsequent retries cannot reapply the purchase. */
    @Test
    void senderPublishesStoredPayloadAndStopsAfterAcknowledgement() {
        UUID stock=receive(store,"P-1",10,"120.00"),cart=cart(store,stock,3);
        Submission accepted=submissions.submit(store,cart,"publish",new SubmitInput(1)); JsonNode expected=event(accepted.submissionId());
        sender().sendOne(); sender().sendOne();
        Submission published=operations.view(store,accepted.submissionId());
        assertThat(published.publicationStatus()).isEqualTo("PUBLISHED"); assertThat(published.publishedAt()).isNotNull();
        assertThat(orders(accepted.eventId(),1)).containsExactly(codec.json(expected));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM store_outbox WHERE submission_id=?",Integer.class,accepted.submissionId())).isEqualTo(1);
        assertThat(quantity(stock)).isEqualTo(7); assertThat(count("stock_expenses")).isEqualTo(1);
    }

    /** A maximum-sized valid Unicode cart fixture produces a >1 MiB OrderSubmitted, reaching real Kafka without truncation. */
    @Test
    void thousandLineOrderTraversesKafkaWithoutTruncation() {
        receive(store,"P-0",2,"120.00"); String name="🛒".repeat(255);
        jdbc.update("UPDATE inventory SET short_name=? WHERE store_id=?",name,store);
        jdbc.update("""
                INSERT INTO inventory(stock_item_id,store_id,product_id,product_type,short_name,description,unit_price,currency,available_quantity,last_delivery_sequence)
                SELECT gen_random_uuid(),?,'P-'||n,'NON_FOOD',?,'',120.00,'RUB',2,1 FROM generate_series(1,999) n
                """,store,name);
        UUID cart=carts.create(store).cartId();
        jdbc.update("INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) SELECT store_id,?,stock_item_id,1 FROM inventory WHERE store_id=?",cart,store);
        jdbc.update("UPDATE carts SET version=1000 WHERE cart_id=?",cart);
        Submission accepted=submissions.submit(store,cart,"large",new SubmitInput(1000));
        String payload=jdbc.queryForObject("SELECT payload FROM store_outbox WHERE submission_id=?",String.class,accepted.submissionId());
        assertThat(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(1048576);
        sender().sendOne();
        assertThat(orders(accepted.eventId(),1)).containsExactly(payload);
        assertThat(carts.get(store,cart).items()).hasSize(1000); assertThat(carts.get(store,cart).totalAmount()).isEqualTo("120000.00");
        assertThat(count("stock_expenses")).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT min(available_quantity) FROM inventory WHERE store_id=?",Integer.class,store)).isEqualTo(1);
    }

    /** A paused real Kafka broker keeps acceptance committed/PENDING, persists backoff and recovers without another expense. */
    @Test
    void kafkaOutagePreservesAcceptedExpenseAndRecovers() {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,3);
        Submission accepted=submissions.submit(store,cart,"outage",new SubmitInput(1));
        var client=broker.getDockerClient(); client.pauseContainerCmd(broker.getContainerId()).exec();
        try {
            sender().sendOne();
            assertThat(operations.view(store,accepted.submissionId()).publicationStatus()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT last_error FROM store_outbox WHERE submission_id=?",String.class,accepted.submissionId())).isEqualTo("Kafka acknowledgement unavailable");
            assertThat(jdbc.queryForObject("SELECT next_attempt_at>accepted_at FROM store_outbox JOIN submissions USING(submission_id) WHERE submission_id=?",Boolean.class,accepted.submissionId())).isTrue();
            assertThat(quantity(stock)).isEqualTo(2);
        } finally { client.unpauseContainerCmd(broker.getContainerId()).exec(); }
        jdbc.update("UPDATE store_outbox SET next_attempt_at=now() WHERE submission_id=?",accepted.submissionId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            sender().sendOne();
            assertThat(operations.view(store,accepted.submissionId()).publicationStatus()).isEqualTo("PUBLISHED");
        });
        assertThat(quantity(stock)).isEqualTo(2); assertThat(count("stock_expenses")).isEqualTo(1);
        assertThat(orders(accepted.eventId(),1).getFirst()).isEqualTo(codec.json(event(accepted.submissionId())));
    }

    /** A SQL failure after real broker ack allows physical duplicate records with exactly the same event/payload and one expense. */
    @Test
    void lostPublishedMarkReplaysSameEventWithoutNewDeduction() {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,3);
        Submission accepted=submissions.submit(store,cart,"ack-loss",new SubmitInput(1));
        failTrigger("store_outbox","UPDATE","NEW.publication_status='PUBLISHED'");
        assertThatThrownBy(() -> sender().sendOne()).isInstanceOf(DataAccessException.class);
        assertThat(operations.view(store,accepted.submissionId()).publicationStatus()).isEqualTo("PENDING");
        jdbc.execute("DROP TRIGGER test_fail_submission ON store_outbox");
        jdbc.update("UPDATE store_outbox SET lease_until=now()-interval '1 second' WHERE submission_id=?",accepted.submissionId()); sender().sendOne();
        assertThat(orders(accepted.eventId(),2)).containsExactly(codec.json(event(accepted.submissionId())),codec.json(event(accepted.submissionId())));
        assertThat(quantity(stock)).isEqualTo(2); assertThat(count("stock_expenses")).isEqualTo(1);
    }

    /** Concurrent claims have one owner; expired ownership may be reclaimed while stale completion/failure cannot modify the new lease. */
    @Test
    void persistedLeaseFencesConcurrentAndStaleSenders() throws Exception {
        UUID stock=receive(store,"P-1",5,"120.00"),cart=cart(store,stock,1);
        Submission accepted=submissions.submit(store,cart,"lease",new SubmitInput(1)); CountDownLatch start=new CountDownLatch(1);
        OutboxWork claimed;
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { awaitStart(start); return operations.claimOutbox(); });
            var b=pool.submit(() -> { awaitStart(start); return operations.claimOutbox(); }); start.countDown();
            var left=a.get(10,TimeUnit.SECONDS); var right=b.get(10,TimeUnit.SECONDS);
            assertThat(left.isPresent()^right.isPresent()).isTrue(); claimed=left.or(() -> right).orElseThrow();
        }
        jdbc.update("UPDATE store_outbox SET lease_until=now()-interval '1 second' WHERE submission_id=?",accepted.submissionId());
        OutboxWork current=operations.claimOutbox().orElseThrow(); operations.published(claimed); operations.failedSend(claimed);
        assertThat(operations.view(store,accepted.submissionId()).publicationStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM store_outbox WHERE event_id=?",UUID.class,accepted.eventId())).isEqualTo(current.leaseToken());
        assertThat(current.payload()).isEqualTo(claimed.payload()); assertThat(current.attemptCount()).isEqualTo(2);
    }

    /** Creates a real consistent posted receipt with independent identifiers and evolving product names; no tariff server is required. */
    private UUID receive(String scope,String product,int quantity,String price) {
        long current=++sequence; UUID id=UUID.randomUUID();
        String raw="""
                {"eventId":"%s","eventType":"GoodsPosted","schemaVersion":1,"occurredAt":"2026-10-03T10:00:02Z","storeId":"%s",
                "payload":{"deliveryId":"D-%s","deliverySequence":%d,"receivedAt":"2026-10-03T10:00:01Z","postedAt":"2026-10-03T10:00:02Z",
                "items":[{"lineId":"L-1","productId":"%s","productType":"NON_FOOD","shortName":"Product-%d","description":"",
                "quantity":%d,"purchasePrice":"%s","currency":"RUB","markupRate":"0.0",
                "tariffRuleId":"b3000000-0000-4000-8000-000000000001","tariffVersion":1,"salePrice":"%s"}]}}
                """.formatted(id,scope,id,current,product,current,quantity,price,price);
        receiver.receive("submission-test",0,current,scope,raw);
        return jdbc.queryForObject("SELECT stock_item_id FROM inventory WHERE store_id=? AND product_id=?",UUID.class,scope,product);
    }
    /** Builds one independent OPEN basket by production create/put transactions, ready at version one. */
    private UUID cart(String scope,UUID stock,int quantity) {
        UUID cart=carts.create(scope).cartId(); carts.put(scope,cart,stock,new PutItem(quantity,0)); return cart;
    }
    /** Reads a precise current quantity for a scenario-owned UUID, independently of HTTP snapshots. */
    private int quantity(UUID stock) { return jdbc.queryForObject("SELECT available_quantity FROM inventory WHERE stock_item_id=?",Integer.class,stock); }
    /** Counts only the current scenario's store; table arguments are fixed test constants. */
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE store_id=?",Integer.class,store); }
    /** Reads the committed immutable event evidence from outbox without rebuilding its financial snapshot. */
    private JsonNode event(UUID id) { return codec.read(jdbc.queryForObject("SELECT payload FROM store_outbox WHERE submission_id=?",String.class,id)); }
    /** Calls real acceptance with a canonical JSON version; failures still exercise HTTP/transaction mapping. */
    private ResponseEntity<JsonNode> submit(String scope,UUID cart,String key,long version) {
        return rawSubmit(scope,cart,key,"{\"expectedCartVersion\":"+version+"}");
    }
    /** Sends exact wire bytes/key, allowing malformed body and missing-header cases to reach production validation. */
    private ResponseEntity<JsonNode> rawSubmit(String scope,UUID cart,String key,String raw) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(key!=null) headers.set("Idempotency-Key",key);
        return http.exchange("/stores/"+scope+"/carts/"+cart+"/submit",HttpMethod.POST,new HttpEntity<>(raw,headers),JsonNode.class);
    }
    /** Asserts named safe errors rather than accepting SQL/stack trace details as an HTTP result. */
    private void assertError(ResponseEntity<JsonNode> result,int status,String code) {
        assertThat(result.getStatusCode().value()).isEqualTo(status); assertThat(result.getBody().path("code").asText()).isEqualTo(code);
        assertThat(result.getBody().toString()).doesNotContain("jdbc:","password","SELECT ","stackTrace");
    }
    /** Uses the real production sender directly for deterministic crash boundaries; scheduled recovery is covered separately. */
    private StoreSender sender() { return new StoreSender(operations,kafka); }
    /** Reads only matching eventId records from real Kafka within 15 seconds, checking the store key on every matching record. */
    private List<String> orders(UUID id,int wanted) {
        Map<String,Object> config=new HashMap<>(); config.put("bootstrap.servers",broker.getBootstrapServers());
        config.put("group.id","test-"+UUID.randomUUID()); config.put("enable.auto.commit",false);
        config.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        config.put("max.partition.fetch.bytes",16777216);
        List<String> found=new ArrayList<>();
        try(KafkaConsumer<String,String> consumer=new KafkaConsumer<>(config)) {
            TopicPartition partition=new TopicPartition("store.order-submitted",0); consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(found.size()<wanted && System.nanoTime()<deadline) {
                for(var record:consumer.poll(Duration.ofMillis(200))) {
                    if(codec.read(record.value()).path("eventId").asText().equals(id.toString())) {
                        assertThat(record.key()).isEqualTo(store); found.add(record.value());
                    }
                }
            }
        }
        assertThat(found.size()).isGreaterThanOrEqualTo(wanted); return found;
    }
    /** Injects a failure at an actual SQL write boundary in this test database; conditional UPDATE models ack→mark loss. */
    private void failTrigger(String table,String operation,String condition) {
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_submit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled purchase storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_submission BEFORE "+operation+" ON "+table+" FOR EACH ROW "+
                (condition==null?"":"WHEN ("+condition+") ")+"EXECUTE FUNCTION test_fail_submit()");
    }
    /** Synchronizes competing HTTP requests without injecting latency into normal application logic. */
    private void awaitStart(CountDownLatch start) {
        try { if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("Competition did not start"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
