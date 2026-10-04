import java.time.Duration
import java.util.concurrent.locks.LockSupport

/** Polls a stated invariant using monotonic time; startup/read failures remain distinct from a healthy timeout. */
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

/** Turns an absent required result into a subject-specific assertion failure instead of an unhelpful NPE. */
fun <T : Any> required(value: T?, subject: String): T {
    return value ?: throw AssertionError("Required result is absent: $subject")
}
