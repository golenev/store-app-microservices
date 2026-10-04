package org.golenev.utils.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import java.util.*

/** Свойства producer из резервной копии без дополнительных настроек доставки. */
class KafkaProps(private val bootstrapServers: String) {
    private val properties = mutableMapOf<String, Any>()

    /** Сохраняет свойство для следующего создания producer. */
    fun put(key: String, value: Any) {
        properties[key] = value
    }

    /** Возвращает Properties с адресом брокера и сохранёнными свойствами. */
    fun toProperties(): Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        putAll(properties)
    }
}
