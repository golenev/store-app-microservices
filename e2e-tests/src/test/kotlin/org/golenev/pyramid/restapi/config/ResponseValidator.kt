package org.golenev.pyramid.restapi.config

import org.golenev.pyramid.restapi.PyramidResponse
import org.junit.jupiter.api.Assertions.assertEquals

/** Проверяет HTTP-статус с исходным телом в диагностике; бизнес-поля проверяет сам тест. */
object ResponseValidator {
    /** Сравнивает фактический ответ с заданным сценарием статусом; при расхождении показывает тело ответа. */
    fun status(response: PyramidResponse, expected: Int) {
        assertEquals(expected, response.statusCode(), response.body())
    }
}
