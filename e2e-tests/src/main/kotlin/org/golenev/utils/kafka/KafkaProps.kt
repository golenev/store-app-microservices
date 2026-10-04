package org.golenev.utils.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import java.util.Properties

/** Настройки отдельного отправителя. Общей изменяемой конфигурации Kafka нет. */
class KafkaProps(private val bootstrapServers: String) {
    private val properties = mutableMapOf<String, Any>()
    /** Задаёт свойство до создания собственного клиента отправителя. */
    fun put(key: String, value: Any) { properties[key] = value }
    /** Возвращает копию настроек брокера и ограничений времени доставки. Последующие изменения подготовки не влияют на работающий отправитель. */
    fun toProperties(): Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000)
        put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000)
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000)
        put(ProducerConfig.ACKS_CONFIG, "all")
        putAll(properties)
    }
}
