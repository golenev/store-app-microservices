package org.golenev.utils.kafka.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.golenev.utils.JsonUtils
import java.util.Properties

/** Настройки локального наблюдателя на основе ConsumerKafkaConfig из KafkaConsumerImpl. Группа и запись offset не используются. */
class ConsumerKafkaConfig(
    private val bootstrapServers: String,
    val awaitTopic: String,
    val awaitMapper: ObjectMapper = JsonUtils.objectMapper,
    val awaitLastNPerPartition: Int = 0,
) {
    /** Создаёт независимого читателя всех партиций с лимитом сообщений, соответствующим контракту магазина. */
    fun toProperties(): Properties = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 16777216)
        put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 16777216)
        put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000)
        put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000)
    }
}
