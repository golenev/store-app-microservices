package org.golenev.utils.kafka

import io.qameta.allure.Allure
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.golenev.utils.JsonUtils
import org.slf4j.LoggerFactory

/** Отправитель из резервной копии: общий mapper JsonUtils, вложение Allure, callback и flush для каждого сообщения. */
abstract class AbstractKafkaProducer(private val config: KafkaProps) {
    private val logger = LoggerFactory.getLogger(AbstractKafkaProducer::class.java)

    /** Сериализует сообщение общим mapper, отправляет с ключом магазина и записывает результат callback в журнал; готовая строка передаётся без повторной сериализации. */
    fun <T> sendMessage(topic: String, key: String, message: T) {
        val json = try {
            if (message is String) message else JsonUtils.objectMapper.writeValueAsString(message)
        } catch (e: Exception) {
            logger.error("Не удалось сериализовать сообщение для топика {}: {}", topic, e.message, e)
            return
        }
        Allure.addAttachment("Сообщение Kafka в $topic", "application/json", json)
        logger.info("Подготовка к отправке сообщения в топик '{}' с ключом {}", topic, key)
        send(topic, key, json)
    }

    /** Создаёт producer, отправляет запись, ожидает завершения через flush и закрывает клиент. */
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
