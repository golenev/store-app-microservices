package org.golenev.restapi.endpoints

import org.golenev.config.Environment
import org.golenev.restapi.config.RequestExecutor
import org.golenev.restapi.config.Reply
import org.golenev.commondto.RuleInput

/** REST-операции сервиса TARIFFS в изолированном окружении; каждый запрос имеет собственную спецификацию. */
class TariffsServiceDao : RequestExecutor(Environment.TARIFFS_URL) {
    /** Создаёт правило по явно переданному контракту; проверка ожидаемого статуса остаётся в сценарии. */
    fun createRule(body: RuleInput): Reply = request("/tariffs/rules", "POST", body)

    /** Изменяет существующее правило; сброс кеша является отдельным действием сценария. */
    fun updateRule(ruleId: String, body: RuleInput): Reply = request("/tariffs/rules/$ruleId", "PUT", body)

    /** Читает конкретное правило для проверки его наличия либо удаления. */
    fun getRule(ruleId: String): Reply = request("/tariffs/rules/$ruleId")

    /** Удаляет конкретное правило, не затрагивая правила соседних сценариев. */
    fun deleteRule(ruleId: String): Reply = request("/tariffs/rules/$ruleId", "DELETE")

    /** Сбрасывает общий кеш своего изолированного окружения; сценарий отвечает за сохранение и восстановление снимка. */
    fun resetCache(): Reply = request("/tariffs/cache/reset", "POST")
}
