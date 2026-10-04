package org.golenev.commondto

/** Безопасное описание ошибки поля в API TARIFFS. Отклонённое значение входных данных не возвращается. */
data class ErrorDetail(val field: String, val message: String)
