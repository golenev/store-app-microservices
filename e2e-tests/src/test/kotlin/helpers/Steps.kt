package helpers

import io.qameta.allure.Allure

/** Records a descriptive test step and propagates its original result/failure; no business action is retried implicitly. */
fun <T> step(description: String, block: () -> T): T = Allure.step(description, block)
