package com.shop.warehouse.config;

import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Настраивает топики и повторы Kafka; сообщения передаёт сервису отдельный listener. */
@Configuration
public class DeliveryKafkaConfig {
    /**
     * Объявляет входящий и исходящий топики с согласованным лимитом размера сообщений.
     * Поддерживает запуск приложений из IDEA и Compose.
     * @return определения топиков сервиса
     */
    @Bean
    public KafkaAdmin.NewTopics warehouseTopics() {
        return new KafkaAdmin.NewTopics(TopicBuilder.name("logistics.deliveries").partitions(1).replicas(1)
                .config("max.message.bytes", "16777216").build(),
                TopicBuilder.name("warehouse.goods-posted").partitions(1).replicas(1)
                .config("max.message.bytes", "16777216").build());
    }

    /**
     * Возвращает неограниченные повторы ошибок хранения; подтверждение смещения не подменяет сохранение
     * результата или диагностики.
     */
    @Bean
    public DefaultErrorHandler warehouseErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class, true), true);
        return handler;
    }

}
