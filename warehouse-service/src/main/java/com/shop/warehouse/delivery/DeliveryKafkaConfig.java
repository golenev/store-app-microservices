package com.shop.warehouse.delivery;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.time.*;

/** Single-consumer ingress; database failures retry indefinitely and never get recovered by silently committing offsets. */
@Configuration
public class DeliveryKafkaConfig {
    private final DeliveryStore store;
    /** Receives durable ingress storage explicitly; Kafka listener parameters remain limited to record metadata. */
    public DeliveryKafkaConfig(DeliveryStore store) { this.store = store; }
    /** Declares both service topics for direct IDEA execution and Compose; broker configuration supplies replication defaults. */
    @Bean
    public KafkaAdmin.NewTopics warehouseTopics() {
        return new KafkaAdmin.NewTopics(TopicBuilder.name("logistics.deliveries").partitions(1).replicas(1)
                .config("max.message.bytes", "16777216").build(),
                TopicBuilder.name("warehouse.goods-posted").partitions(1).replicas(1)
                .config("max.message.bytes", "16777216").build());
    }

    /** Uses millisecond UTC instants so PostgreSQL and serialized event timestamps represent the same exact value. */
    @Bean
    public static Clock warehouseClock() { return Clock.tickMillis(ZoneOffset.UTC); }

    /** Retries every storage failure without a finite recovery path; poison input is already durably diagnosed by receive. */
    @Bean
    public DefaultErrorHandler warehouseErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class, true), true);
        return handler;
    }

    /** Passes raw bytes-as-string and Kafka coordinates into one atomic accept/diagnostic transaction before RECORD acknowledgement. */
    @KafkaListener(topics="logistics.deliveries", groupId="warehouse-deliveries-v1",
            autoStartup="${warehouse.listener.enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        store.receive(record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
