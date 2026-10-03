package com.shop.warehouse;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.warehouse.delivery.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Restarts full independent Spring applications against preserved test databases and real Kafka, with no user-owned volumes. */
@Testcontainers
class WarehouseRecoveryTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer broker=new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    /** A persisted waiting/leased attempt and a posted unsent event both recover automatically after application restart. */
    @Test
    void restartAutomaticallyRecoversPricingLeaseAndPendingOutbox() {
        WireMockServer server=new WireMockServer(wireMockConfig().dynamicPort()); server.start();
        String waiting="D-wait-"+UUID.randomUUID(), posted="D-post-"+UUID.randomUUID();
        long sequence;
        String received, originalEvent;
        try {
            server.stubFor(get(urlPathEqualTo("/tariffs/quote")).willReturn(okJson(quote())));
            try(ConfigurableApplicationContext first=start(server.port(),false)) {
                DeliveryStore store=first.getBean(DeliveryStore.class);
                DeliveryWorkers manual=new DeliveryWorkers(store,first.getBean(TariffClient.class),
                        (KafkaTemplate<String,String>)first.getBean(KafkaTemplate.class));
                store.receive("test",0,1,"S-1",event(posted)); manual.priceOne();
                JdbcTemplate jdbc=first.getBean(JdbcTemplate.class);
                originalEvent=jdbc.queryForObject("SELECT payload FROM warehouse_outbox WHERE delivery_id=?",String.class,posted);
                store.receive("test",0,2,"S-1",event(waiting));
                var original=store.view("S-1",waiting);
                sequence=original.deliverySequence(); received=original.receivedAt().toString();
                store.claimPricing().orElseThrow();
                jdbc.update("UPDATE deliveries SET lease_until=now()-interval '1 second' WHERE delivery_id=?",waiting);
            }
            try(ConfigurableApplicationContext restarted=start(server.port(),true)) {
                DeliveryStore store=restarted.getBean(DeliveryStore.class);
                JdbcTemplate jdbc=restarted.getBean(JdbcTemplate.class);
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(store.view("S-1",waiting).state()).isEqualTo("POSTED");
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_outbox WHERE publication_status='PUBLISHED' AND delivery_id IN (?,?)",Integer.class,waiting,posted)).isEqualTo(2);
                });
                var result=store.view("S-1",waiting);
                assertThat(result.deliverySequence()).isEqualTo(sequence);
                assertThat(result.receivedAt().toString()).isEqualTo(received);
                assertThat(result.attemptCount()).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT payload FROM warehouse_outbox WHERE delivery_id=?",String.class,posted)).isEqualTo(originalEvent);
            }
        } finally { server.stop(); }
    }

    /** After an actual tariff outage the scheduled worker retries autonomously when the rule appears, without manual API calls. */
    @Test
    void missingRuleRecoversWithAutomaticPersistedBackoff() {
        WireMockServer server=new WireMockServer(wireMockConfig().dynamicPort()); server.start();
        String delivery="D-auto-"+UUID.randomUUID();
        try {
            server.stubFor(get(urlPathEqualTo("/tariffs/quote")).willReturn(aResponse().withStatus(404)));
            try(ConfigurableApplicationContext app=start(server.port(),true)) {
                DeliveryStore store=app.getBean(DeliveryStore.class);
                store.receive("test",0,3,"S-2",event(delivery).replace("\"S-1\"","\"S-2\""));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    var waiting=store.view("S-2",delivery);
                    assertThat(waiting.state()).isEqualTo("WAITING_PRICING");
                    assertThat(waiting.lastError()).isNotNull();
                    assertThat(waiting.lastError().code()).isEqualTo("TARIFF_NOT_FOUND");
                });
                server.resetAll(); server.stubFor(get(urlPathEqualTo("/tariffs/quote")).withQueryParam("cityId",equalTo("SPB"))
                        .willReturn(okJson(quote())));
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(store.view("S-2",delivery).state()).isEqualTo("POSTED"));
                assertThat(store.view("S-2",delivery).attemptCount()).isGreaterThanOrEqualTo(2);
            }
        } finally { server.stop(); }
    }

    /** Starts a fresh real application process-equivalent context with explicit container endpoints and selected worker startup mode. */
    private ConfigurableApplicationContext start(int tariffPort,boolean workers) {
        return new SpringApplicationBuilder(WarehouseServiceApplication.class).run(
                "--server.port=0", "--spring.datasource.url="+postgres.getJdbcUrl(),
                "--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
                "--spring.kafka.bootstrap-servers="+broker.getBootstrapServers(),"--tariffs.base-url=http://localhost:"+tariffPort,
                "--warehouse.workers.enabled="+workers,"--warehouse.listener.enabled=false",
                "--warehouse.pricing-poll-ms=100","--warehouse.sender-poll-ms=100");
    }

    /** Generates an independent supplier document whose identity survives restart and transport replay. */
    private String event(String delivery) {
        return """
                {"eventId":"%s","eventType":"DeliveryReceived","schemaVersion":1,"occurredAt":"2026-10-03T10:00:00Z","storeId":"S-1",
                "payload":{"deliveryId":"%s","items":[{"lineId":"L-1","productId":"P-1","productType":"NON_FOOD","shortName":"Soap",
                "description":"","quantity":10,"purchasePrice":"100.00","currency":"RUB"}]}}
                """.formatted(UUID.randomUUID(),delivery);
    }

    /** Provides the canonical tariff response used by the real HTTP client after dependency recovery. */
    private String quote() {
        return "{\"markupRate\":\"0.20\",\"tariffRuleId\":\"b3000000-0000-4000-8000-000000000001\",\"tariffVersion\":1}";
    }
}
