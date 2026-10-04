package org.golenev.utils

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.nondeterministic.eventuallyConfig
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/** Ограниченное ожидание состояния по образцу calculation-service-tests: до 45 секунд с интервалом 2 секунды. */
private val positiveConfig = eventuallyConfig {
    duration = 45.seconds
    interval = 2.seconds
}

/**
 * Повторяет [assertion] до успешного результата: максимум 45 секунд с интервалом 2 секунды.
 * Внутри блока тест явно читает состояние и проверяет его матчерами Kotest.
 * Возвращает значение успешной попытки. По окончании срока Kotest передаёт ошибку ожидания
 * с диагностикой проверок; правила повторения ошибок определяет eventually.
 * Блок выполняется синхронно для вызывающего теста через runBlocking и может запускаться
 * несколько раз, поэтому не должен создавать поставки или повторять оформление заказа.
 */
fun <T> awaitPoll(assertion: () -> T): T = runBlocking {
    eventually(positiveConfig) { assertion() }
}
