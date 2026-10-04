package org.golenev.utils

import org.golenev.utils.awaitState
import org.golenev.utils.required
import org.golenev.utils.JsonUtils
import org.golenev.restapi.endpoints.*
import org.golenev.config.Environment
import io.qameta.allure.Allure
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit


/** Синхронный наблюдатель одного сценария. Позиции конца журнала фиксируются до проверяемого действия. */
class KafkaObservation private constructor(private val consumer: KafkaConsumer<String, String>, private val topic: String,
                                           private val key: String, private val start: Map<TopicPartition, Long>) : AutoCloseable {
    private val observed = mutableListOf<ObservedEvent>()

    companion object {
        /** Назначает все разделы и фиксирует их текущие конечные смещения. При ошибке подготовки закрывает уже созданный клиент. */
        fun open(topic: String, key: String): KafkaObservation {
            val consumer = KafkaConsumer<String, String>(mapOf("bootstrap.servers" to Environment.KAFKA_BOOTSTRAP,
                "group.id" to "e2e-observer-${UUID.randomUUID()}", "enable.auto.commit" to false,
                "key.deserializer" to StringDeserializer::class.java, "value.deserializer" to StringDeserializer::class.java))
            try {
                val partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).map { TopicPartition(topic, it.partition()) }
                check(partitions.isNotEmpty()) { "Observer startup: no partitions topic=$topic" }
                consumer.assign(partitions)
                val start = consumer.endOffsets(partitions, Duration.ofSeconds(10))
                start.forEach { (partition, offset) -> consumer.seek(partition, offset) }
                partitions.forEach { partition -> check(consumer.position(partition) == start[partition]) }
                return KafkaObservation(consumer, topic, key, start)
            } catch (failure: Throwable) {
                try { consumer.close(Duration.ofSeconds(5)) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    /** Читает целевые записи, сохраняя ошибки брокера и разбора. Чужие ключи отбрасываются до разбора тела. */
    private fun poll(): List<ObservedEvent> {
        for (record in consumer.poll(Duration.ofMillis(100))) {
            if (record.key() != key) continue
            val raw = required(record.value(), "non-tombstone target topic=$topic, key=$key, offset=${record.offset()}")
            val json = JsonUtils.objectMapper.readTree(raw)
            val eventId = json.get("eventId")
            check(eventId != null && eventId.isTextual) { "Target event parse: topic=$topic, key=$key, offset=${record.offset()}, raw=$raw" }
            check(observed.size < 256) { "Observer overflow topic=$topic, key=$key, positions=${positions()}" }
            observed += ObservedEvent(key, eventId.asText(), record.partition(), record.offset(), raw)
        }
        return observed.toList()
    }

    /** Возвращает текущие позиции назначенных разделов для диагностики и проверяет возможность их чтения. */
    private fun positions(): Map<TopicPartition, Long> {
        return consumer.assignment().associateWith { consumer.position(it, Duration.ofSeconds(5)) }
    }

    /** Ждёт не меньше указанного числа физических копий события. Сохраняет повторы и не ограничивает их количество сверху. */
    fun copies(eventId: String, minimum: Int): List<ObservedEvent> {
        require(minimum > 0)
        try {
            val records = awaitState("Kafka topic=$topic key=$key event=$eventId minimum=$minimum start=$start",
                read = { poll().filter { it.eventId == eventId } }, ready = { it.size >= minimum })
            Allure.addAttachment("Записи Kafka для события $eventId", "application/json", JsonUtils.objectMapper.writeValueAsString(records))
            return records
        } catch (failure: Throwable) {
            Allure.addAttachment("Ошибка наблюдателя Kafka", "topic=$topic key=$key event=$eventId observed=${observed.size} start=$start")
            throw failure
        }
    }

    /** Проверяет точное количество событий на протяжении полного заданного окна. Успех относится только к этому интервалу наблюдения. */
    fun exactly(eventId: String, expectedCount: Int, absenceWindow: Duration = Duration.ofSeconds(1)): List<ObservedEvent> {
        copies(eventId, expectedCount)
        val started = System.nanoTime()
        while (System.nanoTime() - started < absenceWindow.toNanos()) {
            val matching = poll().filter { it.eventId == eventId }
            check(matching.size == expectedCount) { "Extra Kafka records event=$eventId expected=$expectedCount actual=${matching.size}, positions=${positions()}" }
        }
        consumer.endOffsets(consumer.assignment(), Duration.ofSeconds(5))
        return observed.filter { it.eventId == eventId }
    }

    /** Закрывает собственный наблюдатель в ограниченное время. Общая группа потребителей или фоновый поток не остаются. */
    override fun close() {
        consumer.close(Duration.ofSeconds(5))
    }
}

/** Ждёт, пока реальный потребитель приложения зафиксирует смещения до текущего конца журнала. После этого можно проверять отсутствие повторного эффекта. */
fun awaitConsumerDrain(topic: String, group: String) {
    AdminClient.create(mapOf("bootstrap.servers" to Environment.KAFKA_BOOTSTRAP)).use { admin ->
        val description = required(admin.describeTopics(listOf(topic)).allTopicNames().get(10, TimeUnit.SECONDS)[topic], "metadata topic=$topic")
        val partitions = description.partitions().map { TopicPartition(topic, it.partition()) }
        val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(10, TimeUnit.SECONDS)
        awaitState("consumer group=$group, topic=$topic, target offsets=$ends", read = {
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
        }, ready = { offsets -> ends.all { (partition, end) -> (offsets[partition]?.offset() ?: -1) >= end.offset() } })
    }
}
