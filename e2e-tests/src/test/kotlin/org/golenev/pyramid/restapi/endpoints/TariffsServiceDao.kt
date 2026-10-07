package org.golenev.pyramid.restapi.endpoints

import org.golenev.pyramid.restapi.PyramidResponse
import org.golenev.pyramid.restapi.config.BaseSpecification
import org.golenev.pyramid.restapi.config.RequestExecutor

/** Самостоятельный HTTP-доступ нового CRUD-набора; прежний TariffsServiceDao защищённого E2E не используется. */
class TariffsServiceDao(baseUrl: String) {
    private val executor = RequestExecutor(BaseSpecification(baseUrl))

    /** Передаёт заданную CRUD-операцию публичному API; значения по умолчанию не скрывают ожидаемые результаты тестов. */
    fun request(method: String, path: String, body: Map<String, Any?>? = null): PyramidResponse =
        executor.execute(method, path, body)
}
