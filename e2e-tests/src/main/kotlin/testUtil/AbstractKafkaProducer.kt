package testUtil

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.TimeUnit

/** Explicit-key producer for both valid events and malformed ingress checks; any serialization/broker failure fails the test. */
abstract class AbstractKafkaProducer(private val config: KafkaProps) {
    /** Publishes the exact supplied text/key (or tombstone) and returns only after acknowledgement within 10 seconds. */
    fun sendMessage(topic: String, key: String, message: String?) {
        KafkaProducer<String, String>(config.toProperties()).use { producer ->
            producer.send(ProducerRecord(topic, key, message)).get(10, TimeUnit.SECONDS)
        }
    }
}
