package org.golenev.utils.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.golenev.config.Environment

/** Конкретный отправитель из резервной копии с адресом брокера текущего E2E-окружения. */
class KafkaProducerImpl : AbstractKafkaProducer(createConfig()) {
    companion object {
        /** Задаёт адрес изолированного брокера и строковые сериализаторы, как в исходном отправителе. */
        private fun createConfig(): KafkaProps = KafkaProps(Environment.KAFKA_BOOTSTRAP).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
    }
}
