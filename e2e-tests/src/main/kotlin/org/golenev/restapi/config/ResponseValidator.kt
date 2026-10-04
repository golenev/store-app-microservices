package org.golenev.restapi.config

import org.junit.jupiter.api.Assertions.assertEquals

/** Проверка статуса отделена от выбора DTO: тело ошибки нельзя разобрать как успешный контракт. */
class ResponseValidator(private val expectedStatus: Int) {
    /** Проверяет статус исходного ответа и возвращает его без изменения; при несовпадении сообщает тело ответа. */
    fun validate(response: Reply): Reply {
        assertEquals(expectedStatus, response.status, "Статус HTTP; ответ=${response.raw}")
        return response
    }
}
