package com.shop.store.shop;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.time.*;

/** GoodsPosted consumer acknowledges each record only after atomic inventory/diagnostic storage succeeds. */
@Configuration
public class ShopKafkaConfig {
    private final GoodsReceiver receiver;
    /** Receives durable ingress explicitly; Kafka method parameters remain raw record metadata. */
    public ShopKafkaConfig(GoodsReceiver receiver) { this.receiver=receiver; }
    /** Supplies millisecond UTC instants compatible with SQL precision and the error/receipt wire contract. */
    @Bean
    public static Clock shopClock() { return Clock.tickMillis(ZoneOffset.UTC); }
    /** Declares upstream and outgoing topics with collection-safe message size for IDEA/Compose execution. */
    @Bean
    public KafkaAdmin.NewTopics goodsTopics() {
        return new KafkaAdmin.NewTopics(TopicBuilder.name("warehouse.goods-posted").partitions(1).replicas(1)
                .config("max.message.bytes","16777216").build(),
                TopicBuilder.name("store.order-submitted").partitions(1).replicas(1).config("max.message.bytes","16777216").build());
    }
    /** Retries storage failures indefinitely; poison input is durably handled before successful listener return. */
    @Bean
    public DefaultErrorHandler shopKafkaErrorHandler() {
        DefaultErrorHandler handler=new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class,true),true); return handler;
    }
    /** Passes exact Kafka key/coordinates and raw JSON into one acceptance transaction, without any tariff calls. */
    @KafkaListener(topics="warehouse.goods-posted",groupId="store-goods-v1",autoStartup="${store.listener.enabled:true}")
    public void receive(ConsumerRecord<String,String> record) {
        receiver.receive(record.topic(),record.partition(),record.offset(),record.key(),record.value());
    }
}
