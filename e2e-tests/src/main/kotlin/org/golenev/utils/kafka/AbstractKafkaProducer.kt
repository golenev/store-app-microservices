package org.golenev.utils.kafka

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.TimeUnit

/** Публикует сообщения с явным ключом, включая некорректные входные данные. Ошибки сериализации или брокера приводят к падению теста. */
abstract class AbstractKafkaProducer(private val config: KafkaProps) {
    /** Отправляет исходный текст и ключ, включая сообщение с null-значением, и ждёт подтверждения Kafka не более 10 секунд. */
    fun sendMessage(topic: String, key: String, message: String?) {
        KafkaProducer<String, String>(config.toProperties()).use { producer ->
            producer.send(ProducerRecord(topic, key, message)).get(10, TimeUnit.SECONDS)
        }
    }
}
