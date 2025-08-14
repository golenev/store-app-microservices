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
            logger.error("Не удалось сериализовать сообщение для топика {}: {}", topic, e.message, e)
            return
        }
        val key = UUID.randomUUID().toString()
        logger.info("Подготовка к отправке сообщения в топик '{}' с ключом {}", topic, key)
        send(topic, key, json)
    }

    private fun send(topic: String, key: String, value: String) {
        logger.info("Подключаемся к Kafka для отправки в топик '{}'", topic)
        KafkaProducer<String, String>(config.toProperties()).use { producer: Producer<String, String> ->
            logger.info("Подключение к Kafka выполнено, создаём запись для топика '{}'", topic)
            val record = ProducerRecord(topic, key, value)
            logger.debug("Запись: key={} value={}", key, value)
            producer.send(record) { metadata, exception ->
                if (exception != null) {
                    logger.error("Не удалось отправить сообщение в топик '{}': {}", topic, exception.message, exception)
                } else {
                    logger.info(
                        "Сообщение доставлено в топик '{}' раздел {} смещение {}",
                        topic, metadata?.partition(), metadata?.offset()
                    )
                }
            }
            producer.flush()
            logger.info("Буфер продюсера очищен для топика '{}'", topic)
        }
        logger.info("Продюсер Kafka закрыт для топика '{}'", topic)
    }
}

