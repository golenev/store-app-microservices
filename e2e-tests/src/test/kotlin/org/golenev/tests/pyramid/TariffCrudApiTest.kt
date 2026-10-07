package org.golenev.tests.pyramid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID
import org.golenev.pyramid.restapi.PyramidResponse
import org.golenev.pyramid.restapi.config.ResponseValidator
import org.golenev.pyramid.restapi.endpoints.TariffsServiceDao

/**
 * Девять CRUD-сценариев через публичный HTTP API полностью развёрнутого TARIFFS.
 * Используются только HTTP-ответы: SQL, Spring beans, Kafka и обвязка защищённых UI-тестов недоступны этому набору.
 * Систему запускает оператор или CI до тестов; отсутствие приложения считается ошибкой, а не пропуском.
 */
@Tag("api")
class TariffCrudApiTest {
    private val baseUrl = (System.getenv("PYRAMID_TARIFFS_URL") ?: "http://localhost:6790").trimEnd('/')
    private val city = "PYR-${UUID.randomUUID()}"
    private val json = ObjectMapper()
    private val tariffs = TariffsServiceDao(baseUrl)
    private val created = mutableSetOf<String>()

    /** Удаляет через API только правила текущего теста. Не удаляет fixtures, чужие правила и не сбрасывает общий кеш. */
    @AfterEach
    fun cleanup() {
        created.forEach { id ->
            val response = request("DELETE", "/tariffs/rules/$id")
            assertTrue(response.statusCode() in listOf(204, 404), "Очистка собственного правила: ${response.statusCode()}")
        }
    }

    /**
     * TAR-CRUD-001-API. Создаём NON_FOOD/RUB, границы 0.00–500.00, ставку 0.20.
     * Проверяем HTTP 201, Location, UUID, version=1 и все поля; повторное GET подтверждает доступность записи.
     */
    @Test @DisplayName("TAR-CRUD-001-API: создание правила версии 1")
    fun createsRule() {
        val result = create()
        assertRule(result, 1, input())
        assertEquals(result, read(result["tariffRuleId"].asText()))
    }

    /** TAR-CRUD-002-API. Создаём правило версии 1 и читаем его по UUID. Полный ответ GET совпадает с ответом POST. */
    @Test @DisplayName("TAR-CRUD-002-API: чтение созданного правила")
    fun readsRule() {
        val existing = create()
        assertEquals(existing, read(existing["tariffRuleId"].asText()))
    }

    /**
     * TAR-CRUD-003-API. Правило версии 1 заменяем: FOOD, границы 5.00–без верхнего предела, ставка 0.30.
     * PUT возвращает 200, исходный UUID, version=2 и все новые поля; GET возвращает тот же результат.
     */
    @Test @DisplayName("TAR-CRUD-003-API: полная замена и версия 2")
    fun replacesRule() {
        val id = create()["tariffRuleId"].asText()
        val response = request("PUT", "/tariffs/rules/$id", replacement())
        ResponseValidator.status(response, 200)
        val result = json.readTree(response.body())
        assertEquals(id, result["tariffRuleId"].asText())
        assertRule(result, 2, replacement())
        assertEquals(result, read(id))
    }

    /** TAR-CRUD-004-API. Создаём правило, удаляем с HTTP 204; последующий GET возвращает NOT_FOUND/404. */
    @Test @DisplayName("TAR-CRUD-004-API: удаление и отсутствие при чтении")
    fun deletesRule() {
        val id = create()["tariffRuleId"].asText()
        assertEquals(204, request("DELETE", "/tariffs/rules/$id").statusCode())
        assertError(request("GET", "/tariffs/rules/$id"), 404, "NOT_FOUND")
    }

    /** TAR-CRUD-005-API. Новый UUID отсутствует. GET возвращает NOT_FOUND/404 без успешного пустого объекта. */
    @Test @DisplayName("TAR-CRUD-005-API: чтение неизвестного UUID")
    fun rejectsMissingRead() { assertError(request("GET", "/tariffs/rules/${UUID.randomUUID()}"), 404, "NOT_FOUND") }

    /** TAR-CRUD-006-API. Допустимая замена отсутствующего UUID возвращает NOT_FOUND/404; GET подтверждает отсутствие записи. */
    @Test @DisplayName("TAR-CRUD-006-API: замена неизвестного UUID")
    fun rejectsMissingUpdate() {
        val id = UUID.randomUUID().toString()
        assertError(request("PUT", "/tariffs/rules/$id", replacement()), 404, "NOT_FOUND")
        assertError(request("GET", "/tariffs/rules/$id"), 404, "NOT_FOUND")
    }

    /** TAR-CRUD-007-API. DELETE для нового отсутствующего UUID возвращает NOT_FOUND/404, а не успешный HTTP 204. */
    @Test @DisplayName("TAR-CRUD-007-API: удаление неизвестного UUID")
    fun rejectsMissingDelete() { assertError(request("DELETE", "/tariffs/rules/${UUID.randomUUID()}"), 404, "NOT_FOUND") }

    /**
     * TAR-CRUD-008-API. POST с равными границами 100.00 возвращает VALIDATION_ERROR/400.
     * Список правил собственного уникального города до и после запроса совпадает; SQL для проверки не используется.
     */
    @Test @DisplayName("TAR-CRUD-008-API: неверное создание ничего не сохраняет")
    fun rejectsInvalidCreate() {
        val before = ownRules()
        assertError(request("POST", "/tariffs/rules", invalid()), 400, "VALIDATION_ERROR")
        assertEquals(before, ownRules())
    }

    /**
     * TAR-CRUD-009-API. Для правила версии 1 отправляем PUT с равными границами.
     * Получаем VALIDATION_ERROR/400; GET подтверждает сохранение всех исходных полей и версии.
     */
    @Test @DisplayName("TAR-CRUD-009-API: неверная замена сохраняет исходное правило")
    fun rejectsInvalidUpdate() {
        val existing = create()
        val id = existing["tariffRuleId"].asText()
        assertError(request("PUT", "/tariffs/rules/$id", invalid()), 400, "VALIDATION_ERROR")
        assertEquals(existing, read(id))
    }

    /** Создаёт правило и регистрирует UUID для адресной очистки. Проверяет 201 и ссылку Location на созданный ресурс. */
    private fun create(): JsonNode {
        val response = request("POST", "/tariffs/rules", input())
        ResponseValidator.status(response, 201)
        val result = json.readTree(response.body())
        val id = result["tariffRuleId"].asText()
        UUID.fromString(id)
        created.add(id)
        assertEquals("/tariffs/rules/$id", response.location)
        return result
    }

    /** Выполняет GET по UUID и возвращает JSON только после проверки успешного HTTP 200. */
    private fun read(id: String): JsonNode {
        val response = request("GET", "/tariffs/rules/$id")
        ResponseValidator.status(response, 200)
        return json.readTree(response.body())
    }

    /** Возвращает исходные поля. Уникальный город изолирует сценарий; его конкретный UUID не меняет бизнес-правило. */
    private fun input(): Map<String, Any?> = mapOf("productType" to "NON_FOOD", "cityId" to city, "currency" to "RUB",
        "lowerBound" to "0.00", "upperBound" to "500.00", "markupRate" to "0.20")
    /** Возвращает полную замену с FOOD, нижней границей 5.00, null верхнего предела и ставкой 0.30. */
    private fun replacement(): Map<String, Any?> = mapOf("productType" to "FOOD", "cityId" to city, "currency" to "RUB",
        "lowerBound" to "5.00", "upperBound" to null, "markupRate" to "0.30")
    /** Возвращает недопустимое правило с одинаковыми границами 100.00, сохраняя остальные исходные поля. */
    private fun invalid(): Map<String, Any?> = input() + mapOf("lowerBound" to "100.00", "upperBound" to "100.00")

    /** Сравнивает весь объект с явно ожидаемыми полями и версией; UUID должен быть корректным и сохраняться при update. */
    private fun assertRule(result: JsonNode, version: Long, fields: Map<String, Any?>) {
        UUID.fromString(result["tariffRuleId"].asText())
        val expected = json.readTree(json.writeValueAsString(fields + mapOf("version" to version, "tariffRuleId" to result["tariffRuleId"].asText())))
        assertEquals(expected, result)
    }

    /** Проверяет HTTP-статус и машинный код; одного статуса недостаточно для различения причин отказа. */
    private fun assertError(response: PyramidResponse, status: Int, code: String) {
        ResponseValidator.status(response, status)
        assertEquals(code, json.readTree(response.body())["code"].asText())
    }

    /** Читает публичный список и отбирает только собственный город: общие fixtures не влияют на проверку отказа. */
    private fun ownRules(): List<JsonNode> {
        val response = request("GET", "/tariffs/rules")
        ResponseValidator.status(response, 200)
        return json.readTree(response.body())["items"].filter { it["cityId"].asText() == city }
    }

    /**
     * Отправляет ограниченный десятью секундами HTTP-запрос к публичному API. Тело сериализуется с явными null.
     * Возвращает необработанный ответ, чтобы каждый сценарий сам определял ожидаемый статус и результат.
     */
    private fun request(method: String, path: String, body: Map<String, Any?>? = null): PyramidResponse {
        return tariffs.request(method, path, body)
    }
}
