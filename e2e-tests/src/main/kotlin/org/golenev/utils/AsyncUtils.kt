package org.golenev.utils

import java.time.Duration
import java.util.concurrent.locks.LockSupport

/** Ожидает явно заданное состояние по монотонному времени. Ошибки запуска и чтения передаются отдельно от истечения срока ожидания. */
fun <T> awaitState(description: String, timeout: Duration = Duration.ofSeconds(40), read: () -> T, ready: (T) -> Boolean): T {
    require(!timeout.isNegative && !timeout.isZero) { "Positive timeout required: $description" }
    val started = System.nanoTime()
    var observed = read()
    while (!ready(observed)) {
        if (System.nanoTime() - started >= timeout.toNanos()) {
            throw AssertionError("Timed out after $timeout: $description; last observed=$observed")
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted: $description")
        LockSupport.parkNanos(Duration.ofMillis(100).toNanos())
        observed = read()
    }
    return observed
}

/** Возвращает обязательный результат или завершает проверку с указанием объекта, вместо неинформативного NullPointerException. */
fun <T : Any> required(value: T?, subject: String): T {
    return value ?: throw AssertionError("Required result is absent: $subject")
}
