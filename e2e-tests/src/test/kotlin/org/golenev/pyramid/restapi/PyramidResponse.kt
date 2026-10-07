package org.golenev.pyramid.restapi

/** Необработанный HTTP-ответ отдельного учебного API-набора: статус, текст JSON и ссылка Location. */
data class PyramidResponse(val status: Int, val payload: String, val location: String?) {
    /** Возвращает фактический HTTP-статус; ожидаемый статус задаёт конкретный сценарий. */
    fun statusCode(): Int = status
    /** Возвращает исходное тело ответа без преобразования денег или удаления полей. */
    fun body(): String = payload
}
