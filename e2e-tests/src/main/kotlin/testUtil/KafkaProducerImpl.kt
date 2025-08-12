package testUtil

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer

class KafkaProducerImpl : AbstractKafkaProducer(createConfig()) {
    companion object {
        private fun createConfig(): KafkaProps = KafkaProps("localhost:9092").apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
    }
}

