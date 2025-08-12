package testUtil

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.UUID

abstract class AbstractKafkaProducer(private val config: KafkaProps) {
    private val mapper = ObjectMapper()

    fun <T> sendMessage(topic: String, message: T) {
        val json = try {
            if (message is String) message else mapper.writeValueAsString(message)
        } catch (e: Exception) {
            return
        }
        val key = UUID.randomUUID().toString()
        send(topic, key, json)
    }

    private fun send(topic: String, key: String, value: String) {
        KafkaProducer<String, String>(config.toProperties()).use { producer: Producer<String, String> ->
            val record = ProducerRecord(topic, key, value)
            producer.send(record) { _, _ -> }
            producer.flush()
        }
    }
}

