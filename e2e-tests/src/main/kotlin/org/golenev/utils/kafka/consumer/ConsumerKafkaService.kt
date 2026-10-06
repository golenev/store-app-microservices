package org.golenev.utils.kafka.consumer

import io.qameta.allure.Allure
import io.qameta.allure.Step
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Адаптация ConsumerKafkaService и WaitForMany из проекта KafkaConsumerImpl.
 * Читает через assign без группы и commit, сохраняя сообщения по бизнес-ключу.
 * start возвращается после фиксации позиции: событие быстрого оформления не теряется.
 * Ошибка чтения или JSON передаётся тесту, а не превращается в молчаливый пропуск.
 */
class ConsumerKafkaService<T : Any>(
    private val cfg: ConsumerKafkaConfig,
    private val clazz: Class<T>,
    private val keySelector: (T) -> String?,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val ready = CompletableFuture<Unit>()
    private val failure = AtomicReference<Throwable?>()
    private val waiter = WaitForMany<String, ConsumedMessage<T>>()
    @Volatile private var consumer: KafkaConsumer<String, String>? = null
    private var threadRef: Thread? = null

    /** Запускает один поток и до 15 секунд ждёт готовность партиций и позиции чтения; при ошибке освобождает ресурсы. */
    @Step("Запускаем наблюдение топика и ждём готовность чтения")
    fun start() {
        check(started.compareAndSet(false, true)) { "Consumer уже запускался" }
        running.set(true)
        threadRef = thread(name = "kafka-await-${cfg.awaitTopic}", isDaemon = true) { consume() }
        try {
            ready.get(15, TimeUnit.SECONDS)
            checkFailure()
        } catch (error: Exception) {
            close()
            throw IllegalStateException("Не удалось начать чтение ${cfg.awaitTopic}", error)
        }
    }

    /** Создаёт KafkaConsumer в единственном владеющем потоке, назначает партиции и передаёт записи в очередь. */
    private fun consume() {
        try {
            KafkaConsumer<String, String>(cfg.toProperties()).use { reader ->
                consumer = reader
                val partitions = reader.partitionsFor(cfg.awaitTopic, Duration.ofSeconds(10))
                    .map { TopicPartition(cfg.awaitTopic, it.partition()) }
                check(partitions.isNotEmpty()) { "У ${cfg.awaitTopic} нет доступных партиций" }
                reader.assign(partitions)
                positionToTailOrLastN(reader, partitions)
                ready.complete(Unit)
                while (running.get()) {
                    reader.poll(Duration.ofMillis(300)).forEach { record ->
                        val raw = requireNotNull(record.value()) { "Пустое сообщение ${record.topic()}@${record.offset()}" }
                        val value = cfg.awaitMapper.readValue(raw, clazz)
                        keySelector(value)?.let { key ->
                            waiter.provide(key, ConsumedMessage(value, record.key(), record.topic(), record.partition(), record.offset(), raw))
                        }
                    }
                }
            }
        } catch (error: WakeupException) {
            if (running.get()) failure.set(error)
        } catch (error: Throwable) {
            failure.set(error)
            ready.completeExceptionally(error)
        } finally {
            consumer = null
            running.set(false)
        }
    }

    /** Фиксирует конец партиций до запуска сценария; при заданном N позволяет прочитать ограниченную историю. */
    private fun positionToTailOrLastN(reader: KafkaConsumer<String, String>, partitions: List<TopicPartition>) {
        require(cfg.awaitLastNPerPartition >= 0)
        val end = reader.endOffsets(partitions, Duration.ofSeconds(10))
        val begin = reader.beginningOffsets(partitions, Duration.ofSeconds(10))
        partitions.forEach { partition ->
            reader.seek(partition, maxOf(begin.getValue(partition), end.getValue(partition) - cfg.awaitLastNPerPartition))
        }
    }

    /** Возвращает прочитанные записи нужной заявки, сохраняя JSON и координаты в Allure; пустой список означает истёкший тайм-аут. */
    @Step("Читаем сообщения топика по ключу заявки {key}")
    fun waitForKeyList(key: String, timeoutMs: Long = 40000, min: Int = 1, max: Int = Int.MAX_VALUE): List<ConsumedMessage<T>> {
        check(started.get()) { "Consumer ещё не запущен" }
        val records = waiter.waitMany(key, timeoutMs, min, max, ::checkFailure)
        records.forEach { record ->
            Allure.addAttachment("${record.topic}-${record.partition}@${record.offset}, key=${record.key}", "application/json", record.rawValue, ".json")
        }
        return records
    }

    /** Немедленно сообщает тесту о падении фонового читателя с исходной причиной. */
    private fun checkFailure() {
        failure.get()?.let { throw IllegalStateException("Ошибка чтения ${cfg.awaitTopic}", it) }
        check(running.get()) { "Чтение ${cfg.awaitTopic} остановлено" }
    }

    /** Прерывает poll и ждёт закрытие соединения владеющим потоком; безопасен после ошибки start и при повторном закрытии. */
    override fun close() {
        running.set(false)
        consumer?.wakeup()
        threadRef?.join(15000)
        check(threadRef?.isAlive != true) { "Не завершился поток чтения ${cfg.awaitTopic}" }
    }
}

/** Создаёт типизированный наблюдатель с явным селектором бизнес-ключа, как runService в KafkaConsumerImpl. */
inline fun <reified T : Any> runService(cfg: ConsumerKafkaConfig, noinline keySelector: (T) -> String?): ConsumerKafkaService<T> =
    ConsumerKafkaService(cfg, T::class.java, keySelector)
