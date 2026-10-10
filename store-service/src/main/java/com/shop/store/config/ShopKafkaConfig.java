package com.shop.store.config;

import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Задаёт каналы сообщений Kafka и повторы при ошибке хранения. Приёмку сообщений выполняет отдельный
 * обработчик.
 */
@Configuration
public class ShopKafkaConfig {
    /**
     * Объявляет входящий и исходящий каналы Kafka и устанавливает лимит размера сообщения для каждого. Эти
     * настройки применяются при запуске сервиса из IDE или Docker Compose.
     *
     * @return определения входящего и исходящего каналов Kafka
     */
    @Bean
    public KafkaAdmin.NewTopics goodsTopics() {
        return new KafkaAdmin.NewTopics(TopicBuilder.name("warehouse.goods-posted").partitions(1).replicas(1)
                .config("max.message.bytes","16777216").build(),
                TopicBuilder.name("store.order-submitted").partitions(1).replicas(1).config("max.message.bytes","16777216").build());
    }
    /**
     * Создаёт обработчик, повторяющий сообщение при ошибке БД без ограничения числа попыток. Неверные входные
     * данные сервис приёмки сначала сохраняет для диагностики; без сохранённого результата сообщение не
     * считается обработанным.
     *
     * @return повтор обработки сообщений при ошибке хранения
     */
    @Bean
    public DefaultErrorHandler shopKafkaErrorHandler() {
        DefaultErrorHandler handler=new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class,true),true); return handler;
    }

}
