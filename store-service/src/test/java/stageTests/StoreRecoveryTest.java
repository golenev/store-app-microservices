package stageTests;

import com.shop.store.StoreServiceApplication;
import com.shop.store.shop.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import static com.shop.store.shop.ShopModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Fresh Spring application contexts preserve container SQL across restart; automatic sender uses actual Kafka. */
@Testcontainers
class StoreRecoveryTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer broker=new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    /** Committed/unclaimed and leased work both publish after restart; the original request recovers its result without another expense. */
    @Test
    void restartRecoversCommittedAndExpiredLeasedOutbox() {
        String product="P-"+UUID.randomUUID(),key="restart-"+UUID.randomUUID();
        Submission first,second; UUID stock; Cart snapshot; String immutable;
        try(ConfigurableApplicationContext app=start(false)) {
            stock=receive(app,product); CartService carts=app.getBean(CartService.class);
            SubmissionService submissions=app.getBean(SubmissionService.class);
            UUID a=carts.create("S-1").cartId(),b=carts.create("S-1").cartId();
            carts.put("S-1",a,stock,new PutItem(3,0)); carts.put("S-1",b,stock,new PutItem(2,0));
            first=submissions.submit("S-1",a,key,new SubmitInput(1));
            second=submissions.submit("S-1",b,"second-"+UUID.randomUUID(),new SubmitInput(1));
            snapshot=carts.get("S-1",a); JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
            immutable=jdbc.queryForObject("SELECT payload FROM store_outbox WHERE submission_id=?",String.class,first.submissionId());
            app.getBean(SubmissionStore.class).claimOutbox().orElseThrow();
            jdbc.update("UPDATE store_outbox SET lease_until=now()-interval '1 second' WHERE submission_id IN (?,?) AND lease_token IS NOT NULL",
                    first.submissionId(),second.submissionId());
        }
        try(ConfigurableApplicationContext restarted=start(true)) {
            SubmissionStore operations=restarted.getBean(SubmissionStore.class); JdbcTemplate jdbc=restarted.getBean(JdbcTemplate.class);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(operations.view("S-1",first.submissionId()).publicationStatus()).isEqualTo("PUBLISHED");
                assertThat(operations.view("S-1",second.submissionId()).publicationStatus()).isEqualTo("PUBLISHED");
            });
            Submission replay=restarted.getBean(SubmissionService.class).submit("S-1",first.cartId(),key,new SubmitInput(1));
            assertThat(replay.submissionId()).isEqualTo(first.submissionId()); assertThat(replay.publicationStatus()).isEqualTo("PUBLISHED");
            assertThat(restarted.getBean(CartService.class).get("S-1",first.cartId())).isEqualTo(snapshot);
            assertThat(jdbc.queryForObject("SELECT payload FROM store_outbox WHERE submission_id=?",String.class,first.submissionId())).isEqualTo(immutable);
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM inventory WHERE stock_item_id=?",Integer.class,stock)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_expenses WHERE stock_item_id=?",Integer.class,stock)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(attempt_count) FROM store_outbox WHERE submission_id IN (?,?)",Integer.class,first.submissionId(),second.submissionId())).isEqualTo(3);
        }
    }

    /** Scheduled sender records an actual broker outage and publishes autonomously after recovery with one committed expense. */
    @Test
    void scheduledSenderAutomaticallyRecoversBrokerOutage() {
        try(ConfigurableApplicationContext app=start(true)) {
            UUID stock=receive(app,"P-"+UUID.randomUUID()); CartService carts=app.getBean(CartService.class);
            UUID cart=carts.create("S-1").cartId(); carts.put("S-1",cart,stock,new PutItem(3,0));
            var client=broker.getDockerClient(); client.pauseContainerCmd(broker.getContainerId()).exec();
            Submission accepted;
            try {
                accepted=app.getBean(SubmissionService.class).submit("S-1",cart,"outage-"+UUID.randomUUID(),new SubmitInput(1));
                JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("SELECT last_error FROM store_outbox WHERE submission_id=?",String.class,accepted.submissionId())).isNotNull());
                assertThat(app.getBean(SubmissionStore.class).view("S-1",accepted.submissionId()).publicationStatus()).isEqualTo("PENDING");
            } finally { client.unpauseContainerCmd(broker.getContainerId()).exec(); }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(app.getBean(SubmissionStore.class).view("S-1",accepted.submissionId()).publicationStatus()).isEqualTo("PUBLISHED"));
            JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM inventory WHERE stock_item_id=?",Integer.class,stock)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_expenses WHERE stock_item_id=?",Integer.class,stock)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM store_outbox WHERE submission_id=?",Integer.class,accepted.submissionId())).isGreaterThanOrEqualTo(2);
        }
    }

    /** Starts a process-equivalent STORE context with explicit container endpoints; no user-owned data or volumes are connected. */
    private ConfigurableApplicationContext start(boolean sender) {
        return new SpringApplicationBuilder(StoreServiceApplication.class).run("--server.port=0",
                "--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),
                "--spring.datasource.password="+postgres.getPassword(),"--spring.kafka.bootstrap-servers="+broker.getBootstrapServers(),
                "--store.listener.enabled=false","--store.sender.enabled="+sender,"--store.sender-poll-ms=100");
    }
    /** Creates a unique stocked product using real ingress transactions and a sequence newer than previous test receipts. */
    private UUID receive(ConfigurableApplicationContext app,String product) {
        JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
        long sequence=jdbc.queryForObject("SELECT coalesce(max(delivery_sequence),0)+1 FROM stock_receipts WHERE store_id='S-1'",Long.class);
        UUID event=UUID.randomUUID();
        String raw="""
                {"eventId":"%s","eventType":"GoodsPosted","schemaVersion":1,"occurredAt":"2026-10-03T10:00:02Z","storeId":"S-1",
                "payload":{"deliveryId":"D-%s","deliverySequence":%d,"receivedAt":"2026-10-03T10:00:01Z","postedAt":"2026-10-03T10:00:02Z",
                "items":[{"lineId":"L-1","productId":"%s","productType":"NON_FOOD","shortName":"Soap","description":"",
                "quantity":10,"purchasePrice":"100.00","currency":"RUB","markupRate":"0.20",
                "tariffRuleId":"b3000000-0000-4000-8000-000000000001","tariffVersion":1,"salePrice":"120.00"}]}}
                """.formatted(event,event,sequence,product);
        app.getBean(GoodsReceiver.class).receive("recovery-test",0,sequence,"S-1",raw);
        return jdbc.queryForObject("SELECT stock_item_id FROM inventory WHERE store_id='S-1' AND product_id=?",UUID.class,product);
    }
}
