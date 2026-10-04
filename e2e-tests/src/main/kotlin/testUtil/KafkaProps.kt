package testUtil

import org.apache.kafka.clients.producer.ProducerConfig
import java.util.Properties

/** Per-producer configuration; no shared mutable global Kafka settings. */
class KafkaProps(private val bootstrapServers: String) {
    private val properties = mutableMapOf<String, Any>()
    /** Sets a producer property before constructing its private client. */
    fun put(key: String, value: Any) { properties[key] = value }
    /** Copies broker and bounded delivery properties so subsequent fixtures cannot mutate a running producer. */
    fun toProperties(): Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000)
        put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000)
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000)
        put(ProducerConfig.ACKS_CONFIG, "all")
        putAll(properties)
    }
}
