package helpers

import awaitState
import required
import config.HttpClient
import constants.Endpoints
import io.qameta.allure.Allure
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Physical broker record, keeping transport identity, offset and exact text separately from its business DTO. */
data class ObservedEvent(val key: String, val eventId: String, val partition: Int, val offset: Long, val raw: String) {
    /** Decodes the declared event contract; a target parse failure is never treated as absence. */
    inline fun <reified T> body(): T = HttpClient.mapper.readValue(raw)
}

/** Synchronous single-owner observer with an acknowledged end position captured before the scenario's action. */
class KafkaObservation private constructor(private val consumer: KafkaConsumer<String, String>, private val topic: String,
                                           private val key: String, private val start: Map<TopicPartition, Long>) : AutoCloseable {
    private val observed = mutableListOf<ObservedEvent>()

    companion object {
        /** Assigns every partition and fixes current end offsets; closes a partially opened observer if readiness fails. */
        fun open(topic: String, key: String): KafkaObservation {
            val consumer = KafkaConsumer<String, String>(mapOf("bootstrap.servers" to Endpoints.KAFKA,
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

    /** Reads relevant records without masking broker/parser failures; unrelated keys are skipped before parsing. */
    private fun poll(): List<ObservedEvent> {
        for (record in consumer.poll(Duration.ofMillis(100))) {
            if (record.key() != key) continue
            val raw = required(record.value(), "non-tombstone target topic=$topic, key=$key, offset=${record.offset()}")
            val json = HttpClient.mapper.readTree(raw)
            val eventId = json.get("eventId")
            check(eventId != null && eventId.isTextual) { "Target event parse: topic=$topic, key=$key, offset=${record.offset()}, raw=$raw" }
            check(observed.size < 256) { "Observer overflow topic=$topic, key=$key, positions=${positions()}" }
            observed += ObservedEvent(key, eventId.asText(), record.partition(), record.offset(), raw)
        }
        return observed.toList()
    }

    /** Reports assigned positions as part of timeout evidence and confirms every assigned partition is readable. */
    private fun positions(): Map<TopicPartition, Long> {
        return consumer.assignment().associateWith { consumer.position(it, Duration.ofSeconds(5)) }
    }

    /** Waits for at least minimum physical copies, preserving multiplicity; does not claim an exact upper bound. */
    fun copies(eventId: String, minimum: Int): List<ObservedEvent> {
        require(minimum > 0)
        try {
            val records = awaitState("Kafka topic=$topic key=$key event=$eventId minimum=$minimum start=$start",
                read = { poll().filter { it.eventId == eventId } }, ready = { it.size >= minimum })
            Allure.addAttachment("Kafka records $eventId", "application/json", HttpClient.mapper.writeValueAsString(records))
            return records
        } catch (failure: Throwable) {
            Allure.addAttachment("Kafka observer failure", "topic=$topic key=$key event=$eventId observed=${observed.size} start=$start")
            throw failure
        }
    }

    /** Requires exact count through a complete absence window with healthy reads; this is bounded evidence, not forever. */
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

    /** Releases this observer within a bounded deadline; no background thread or shared group survives the scenario. */
    override fun close() {
        consumer.close(Duration.ofSeconds(5))
    }
}

/** Waits for the real application consumer to commit through the current acknowledged log end before negative effect assertions. */
fun awaitConsumerDrain(topic: String, group: String) {
    AdminClient.create(mapOf("bootstrap.servers" to Endpoints.KAFKA)).use { admin ->
        val description = required(admin.describeTopics(listOf(topic)).allTopicNames().get(10, TimeUnit.SECONDS)[topic], "metadata topic=$topic")
        val partitions = description.partitions().map { TopicPartition(topic, it.partition()) }
        val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(10, TimeUnit.SECONDS)
        awaitState("consumer group=$group, topic=$topic, target offsets=$ends", read = {
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
        }, ready = { offsets -> ends.all { (partition, end) -> (offsets[partition]?.offset() ?: -1) >= end.offset() } })
    }
}
