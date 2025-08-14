package testUtil

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.util.UUID

abstract class AbstractKafkaProducer(private val config: KafkaProps) {
    private val mapper = ObjectMapper()
    private val logger = LoggerFactory.getLogger(AbstractKafkaProducer::class.java)

    fun <T> sendMessage(topic: String, message: T) {
        val json = try {
            if (message is String) message else mapper.writeValueAsString(message)
        } catch (e: Exception) {
            logger.error("Failed to serialize message for topic {}: {}", topic, e.message, e)
            return
        }
        val key = UUID.randomUUID().toString()
        logger.info("Preparing to send message to topic '{}' with key {}", topic, key)
        send(topic, key, json)
    }

    private fun send(topic: String, key: String, value: String) {
        logger.info("Connecting to Kafka to send to topic '{}'", topic)
        KafkaProducer<String, String>(config.toProperties()).use { producer: Producer<String, String> ->
            logger.info("Connected to Kafka, preparing record for topic '{}'", topic)
            val record = ProducerRecord(topic, key, value)
            logger.debug("Record: key={} value={}", key, value)
            producer.send(record) { metadata, exception ->
                if (exception != null) {
                    logger.error("Failed to send message to topic '{}': {}", topic, exception.message, exception)
                } else {
                    logger.info(
                        "Message delivered to topic '{}' partition {} offset {}",
                        topic, metadata?.partition(), metadata?.offset()
                    )
                }
            }
            producer.flush()
            logger.info("Message flushed for topic '{}'", topic)
        }
        logger.info("Kafka producer closed for topic '{}'", topic)
    }
}

