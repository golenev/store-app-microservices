package org.golenev.utils.kafka.consumer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Очереди по ключу из KafkaConsumerImpl сохраняют сообщение, даже если оно прочитано раньше вызова ожидания. */
class WaitForMany<K : Any, V : Any> {
    private val map = ConcurrentHashMap<K, LinkedBlockingQueue<V>>()

    /** Передаёт прочитанное сообщение ожидающему тесту, не выполняя бизнес-проверок в потоке consumer. */
    fun provide(key: K, value: V) {
        map.computeIfAbsent(key) { LinkedBlockingQueue() }.offer(value)
    }

    /** Ждёт минимум сообщений в пределах одного общего тайм-аута и регулярно проверяет ошибку фонового читателя. */
    fun waitMany(
        key: K,
        timeoutMs: Long,
        min: Int = 1,
        max: Int = Int.MAX_VALUE,
        checkFailure: () -> Unit = {},
    ): List<V> {
        require(timeoutMs >= 0 && min > 0 && max >= min)
        val queue = map.computeIfAbsent(key) { LinkedBlockingQueue() }
        val values = ArrayList<V>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (values.size < min && values.size < max) {
            checkFailure()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            queue.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS)
                ?.let(values::add)
        }
        checkFailure()
        return values
    }
}
