package org.golenev.utils

import io.qameta.allure.Allure

/** Записывает бизнес-шаг или вложенную техническую операцию в Allure. Возвращает результат и сохраняет исходную ошибку. */
fun <T> step(description: String, block: () -> T): T {
    return Allure.step(description, Allure.ThrowableRunnable<T> { block() })
}
