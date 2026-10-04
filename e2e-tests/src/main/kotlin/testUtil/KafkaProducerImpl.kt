package testUtil

import constants.Endpoints
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer

/** String protocol producer targeting only the E2E broker. */
class KafkaProducerImpl : AbstractKafkaProducer(createConfig()) {
    companion object {
        /** Builds independent UTF-8 producer settings; storeId must be supplied as the actual message key. */
        private fun createConfig(): KafkaProps = KafkaProps(Endpoints.KAFKA).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
    }
}
