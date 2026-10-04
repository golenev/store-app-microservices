package org.golenev.commondto

/** Ошибка HTTP по контракту. Текст сообщения служит диагностикой; ожидаемый код проверяется явно. */
data class ApiError(val timestamp: String, val status: Int, val code: String, val message: String, val path: String,
                    val details: List<ErrorDetail>? = null)
