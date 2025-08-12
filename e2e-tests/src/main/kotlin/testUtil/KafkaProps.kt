package testUtil

import org.apache.kafka.clients.producer.ProducerConfig
import java.util.Properties

class KafkaProps(private val bootstrapServers: String) {
    private val properties = mutableMapOf<String, Any>()

    fun put(key: String, value: Any) {
        properties[key] = value
    }

    fun toProperties(): Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        putAll(properties)
    }
}

