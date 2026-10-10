package com.shop.store.config;

import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Настраивает топики и повторы Kafka; обработку сообщений выполняет отдельный listener. */
@Configuration
public class ShopKafkaConfig {
    /**
     * Объявляет входящий и исходящий топики с согласованным лимитом размера сообщений.
     * Поддерживает запуск приложений из IDEA и Compose.
     * @return определения топиков сервиса
     */
    @Bean
    public KafkaAdmin.NewTopics goodsTopics() {
        return new KafkaAdmin.NewTopics(TopicBuilder.name("warehouse.goods-posted").partitions(1).replicas(1)
                .config("max.message.bytes","16777216").build(),
                TopicBuilder.name("store.order-submitted").partitions(1).replicas(1).config("max.message.bytes","16777216").build());
    }
    /**
     * Возвращает обработчик с неограниченными повторами ошибок хранения; невалидный ввод предварительно
     * сохраняется как диагностика.
     */
    @Bean
    public DefaultErrorHandler shopKafkaErrorHandler() {
        DefaultErrorHandler handler=new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class,true),true); return handler;
    }

}
