package org.golenev.commondto

/** Безопасное описание ошибки поля в API TARIFFS. Отклонённое значение входных данных не возвращается. */
data class ErrorDetail(
    /**
     * Имя поля запроса, для которого обнаружена ошибка.
     */
    val field: String,
    /**
     * Диагностическое описание причины ошибки.
     */
    val message: String
)
