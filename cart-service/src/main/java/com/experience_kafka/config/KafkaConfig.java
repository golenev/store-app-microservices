package com.experience_kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
@Slf4j
public class KafkaConfig {

    private final String bootstrapServers;

    public KafkaConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "group-java-test");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1000L, 3));
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.error("Failed to process record {} on attempt {}", record, deliveryAttempt, ex));
        return handler;
    }

    /**
     * Бин постоянно слушает топик и консуммит все новые сообщения
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // Используем восемь параллельных потоков. Kafka закрепляет поток за каждой партицией,
        // поэтому количество потоков должно совпадать с числом партиций (см. sendTopic ниже),
        // чтобы каждый поток читал свою партицию одновременно с другими. Координатор группы
        // распределяет партиции между потоками и не позволяет нескольким потокам читать одну и
        // ту же партицию одновременно; если потоков больше, чем партиций, лишние потоки
        // простаивают. Порядок сообщений внутри партиции сохраняется, а разные партиции
        // обрабатываются параллельно.
        factory.setConcurrency(8);
        return factory;
    }

    @Bean
    public NewTopic sendTopic() {
        // Создаём топик с восемью партициями, чтобы обеспечить работу для восьми потоков
        // слушателя. При отправке сообщения Kafka выбирает партицию по хешу ключа, а при
        // отсутствии ключа распределяет сообщения по принципу round-robin, что равномерно
        // распределяет нагрузку. Каждая партиция читается независимо, поэтому KafkaService может
        // обрабатывать несколько сообщений одновременно и при этом не нарушает порядок внутри
        // каждой партиции.
        return new NewTopic("send-topic", 8, (short) 1);
    }
}
