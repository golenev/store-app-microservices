package org.golenev.utils.kafka

import org.golenev.config.Environment
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer

/** Отправитель строковых сообщений в брокер изолированного E2E-окружения. */
class KafkaProducerImpl : AbstractKafkaProducer(createConfig()) {
    companion object {
        /** Создаёт отдельные настройки отправителя с UTF-8. Ключом сообщения вызывающий код передаёт storeId. */
        private fun createConfig(): KafkaProps = KafkaProps(Environment.KAFKA_BOOTSTRAP).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
    }
}
