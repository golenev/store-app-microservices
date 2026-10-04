import java.time.Duration
import java.util.concurrent.locks.LockSupport

/** Polls an explicit state invariant until its deadline; read failures are not swallowed and the final observed value is reported. */
fun <T> awaitState(description: String, timeout: Duration = Duration.ofSeconds(40), read: () -> T, ready: (T) -> Boolean): T {
    val deadline = System.nanoTime() + timeout.toNanos()
    var observed = read()
    while (!ready(observed)) {
        if (System.nanoTime() >= deadline) throw AssertionError("Timed out: $description; last state=$observed")
        LockSupport.parkNanos(Duration.ofMillis(100).toNanos())
        observed = read()
    }
    return observed
}
