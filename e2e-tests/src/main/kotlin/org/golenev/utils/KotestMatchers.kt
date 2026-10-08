package org.golenev.utils

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/** Сравнивает значения через Kotest и добавляет пояснение проверяемого поля, как в проекте-образце. */
fun <T> T.shouldBe(
    expected: T,
    message: String,
) {
    withClue(message) {
        this shouldBe expected
    }
}
