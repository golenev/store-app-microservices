package helpers

import io.qameta.allure.Allure

/** Records a business or nested technical step, returning its result and preserving the original failure. */
fun <T> step(description: String, block: () -> T): T {
    return Allure.step(description, Allure.ThrowableRunnable<T> { block() })
}
