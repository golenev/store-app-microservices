package org.golenev.tests.backend

import io.kotest.matchers.collections.shouldBeEmpty
import io.qameta.allure.AllureId
import io.restassured.response.Response
import org.golenev.commondto.ApiError
import org.golenev.commondto.RuleInput
import org.golenev.commondto.TariffRule
import org.golenev.restapi.crud.endpoints.TariffCrudServiceDao
import org.golenev.utils.shouldBe
import org.golenev.utils.step
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Девять прежних CRUD-сценариев чёрного ящика по структуре backend-тестов golenev-xlsx-report-system.
 * Действия, разбор data class и проверки Kotest находятся в бизнес-шагах Allure.
 * Внешнее состояние читается только через HTTP. Каждый сценарий получает собственный город.
 */
@Tag("api")
@DisplayName("API: создание, чтение, замена и удаление тарифных правил")
class TariffCrudApiTest {
    private val tariffService = TariffCrudServiceDao()
    private val cityId = "PYR-" + UUID.randomUUID()
    private val createdRuleIds = mutableSetOf<String>()
    private val creationRequest = RuleInput(
        productType = "NON_FOOD", cityId = cityId, currency = "RUB",
        lowerBound = "0.00", upperBound = "500.00", markupRate = "0.20",
    )

    /**
     * Удаляет только UUID, успешно созданные текущим сценарием, через публичный DELETE.
     * Уже удалённое проверкой правило исключается из набора; чужие правила и общий кеш не затрагиваются.
     */
    @AfterEach
    fun cleanup() {
        step("Удаляем тарифные правила, созданные текущим сценарием") {
            createdRuleIds.forEach { ruleId -> tariffService.deleteRule(ruleId) }
        }
    }

    /**
     * TAR-CRUD-001-API. Создаём NON_FOOD/RUB, диапазон 0.00–500.00 и ставку 0.20. Проверяем 201, Location, UUID, все поля версии 1 и сохранение через GET.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-001-API")
    @Test
    @DisplayName("TAR-CRUD-001-API: создание правила версии 1")
    fun createsRule() {

        val response = createRuleTemplate(creationRequest)
        val createdRule = response.`as`(TariffRule::class.java)
        step("Проверяем UUID, Location и все поля созданного правила версии 1") {
            UUID.fromString(createdRule.tariffRuleId)
            response.getHeader("Location").shouldBe("/tariffs/rules/" + createdRule.tariffRuleId, "Location созданного ресурса")
            createdRule.version.shouldBe(1L, "Начальная версия правила")
            createdRule.productType.shouldBe(creationRequest.productType, "Тип товара")
            createdRule.cityId.shouldBe(creationRequest.cityId, "Город правила")
            createdRule.currency.shouldBe(creationRequest.currency, "Валюта")
            createdRule.lowerBound.shouldBe(creationRequest.lowerBound, "Нижняя граница")
            createdRule.upperBound.shouldBe(creationRequest.upperBound, "Верхняя граница")
            createdRule.markupRate.shouldBe(creationRequest.markupRate, "Дробная ставка")
        }
        val savedRule = step("Читаем созданное правило через публичный GET") {
            tariffService.getRule(createdRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем сохранение всех полей после создания") {
            savedRule.shouldBe(createdRule, "GET возвращает созданное правило")
        }
    }

    /**
     * TAR-CRUD-002-API. Подготавливаем правило версии 1. GET по UUID должен вернуть все исходные поля без изменений; сравниваем типизированные ответы целиком.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-002-API")
    @Test
    @DisplayName("TAR-CRUD-002-API: чтение созданного правила")
    fun readsRule() {

        val createdRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        val actualRule = step("Читаем созданное правило по его UUID") {
            tariffService.getRule(createdRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем все поля и версию ответа чтения") {
            actualRule.shouldBe(createdRule, "Ответ GET совпадает с ответом POST")
        }
    }

    /**
     * TAR-CRUD-003-API. Версию 1 заменяем на FOOD, нижнюю границу 5.00, null верхнего предела и ставку 0.30. Проверяем прежний UUID, version=2, все новые поля и их сохранение через GET.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-003-API")
    @Test
    @DisplayName("TAR-CRUD-003-API: полная замена и версия 2")
    fun replacesRule() {

        val originalRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        val replacement = RuleInput(
            productType = "FOOD", cityId = cityId, currency = "RUB",
            lowerBound = "5.00", upperBound = null, markupRate = "0.30",
        )
        val updatedRule = step("Полностью заменяем правило на FOOD, границу 5.00 и ставку 0.30 без верхнего предела") {
            tariffService.updateRule(originalRule.tariffRuleId, replacement).`as`(TariffRule::class.java)
        }
        step("Проверяем прежний UUID, новую версию и все заменённые поля") {
            updatedRule.tariffRuleId.shouldBe(originalRule.tariffRuleId, "UUID сохраняется")
            updatedRule.version.shouldBe(2L, "Версия увеличивается ровно на один")
            updatedRule.productType.shouldBe(replacement.productType, "Новый тип товара")
            updatedRule.cityId.shouldBe(replacement.cityId, "Город после замены")
            updatedRule.currency.shouldBe(replacement.currency, "Валюта после замены")
            updatedRule.lowerBound.shouldBe(replacement.lowerBound, "Новая нижняя граница")
            updatedRule.upperBound.shouldBe(replacement.upperBound, "Верхний предел снят")
            updatedRule.markupRate.shouldBe(replacement.markupRate, "Новая ставка")
        }
        val savedRule = step("Читаем правило после полной замены") {
            tariffService.getRule(originalRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем сохранение всех новых полей и версии") {
            savedRule.shouldBe(updatedRule, "GET возвращает результат PUT")
        }
    }

    /**
     * TAR-CRUD-004-API. Создаём и удаляем правило с HTTP 204. Следующий GET должен вернуть NOT_FOUND/404; ApiError проверяется внутри сценария.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-004-API")
    @Test
    @DisplayName("TAR-CRUD-004-API: удаление и отсутствие при чтении")
    fun deletesRule() {

        val createdRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        step("Удаляем созданное правило с ожидаемым HTTP 204") {
            tariffService.deleteRule(createdRule.tariffRuleId)
            createdRuleIds.remove(createdRule.tariffRuleId)
        }
        val error = step("Читаем удалённое правило и ожидаем HTTP 404") {
            tariffService.getRule(createdRule.tariffRuleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отсутствие удалённого правила") {
            error.status.shouldBe(404, "Статус ошибки отсутствующей записи")
            error.code.shouldBe("NOT_FOUND", "Причина отказа после удаления")
        }
    }

    /**
     * TAR-CRUD-005-API. GET нового отсутствующего UUID возвращает NOT_FOUND/404. Успешный пустой объект не должен заменять ошибку.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-005-API")
    @Test
    @DisplayName("TAR-CRUD-005-API: чтение неизвестного UUID")
    fun rejectsMissingRead() {

        val ruleId = UUID.randomUUID().toString()
        val error = step("Читаем отсутствующее правило и ожидаем HTTP 404") {
            tariffService.getRule(ruleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем статус и причину отказа чтения") {
            error.status.shouldBe(404, "Статус неизвестной записи")
            error.code.shouldBe("NOT_FOUND", "Причина отказа чтения")
        }
    }

    /**
     * TAR-CRUD-006-API. Допустимый PUT неизвестного UUID возвращает NOT_FOUND/404. Последующий GET подтверждает отсутствие записи: замена не должна создавать правило.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-006-API")
    @Test
    @DisplayName("TAR-CRUD-006-API: замена неизвестного UUID")
    fun rejectsMissingUpdate() {

        val ruleId = UUID.randomUUID().toString()
        val replacement = RuleInput(
            productType = "FOOD", cityId = cityId, currency = "RUB",
            lowerBound = "5.00", upperBound = null, markupRate = "0.30",
        )
        val updateError = step("Заменяем отсутствующее правило допустимыми данными и ожидаем HTTP 404") {
            tariffService.updateRule(ruleId, replacement, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ замены отсутствующего правила") {
            updateError.status.shouldBe(404, "Статус отказа PUT")
            updateError.code.shouldBe("NOT_FOUND", "Причина отказа PUT")
        }
        val readError = step("Читаем тот же UUID и проверяем, что PUT не создал правило") {
            tariffService.getRule(ruleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отсутствие записи после неуспешной замены") {
            readError.status.shouldBe(404, "Статус следующего GET")
            readError.code.shouldBe("NOT_FOUND", "Правило по-прежнему отсутствует")
        }
    }

    /**
     * TAR-CRUD-007-API. DELETE нового отсутствующего UUID возвращает NOT_FOUND/404 вместо успешного HTTP 204.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-007-API")
    @Test
    @DisplayName("TAR-CRUD-007-API: удаление неизвестного UUID")
    fun rejectsMissingDelete() {

        val ruleId = UUID.randomUUID().toString()
        val error = step("Удаляем отсутствующее правило и ожидаем HTTP 404") {
            tariffService.deleteRule(ruleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем статус и причину отказа удаления") {
            error.status.shouldBe(404, "Статус отказа DELETE")
            error.code.shouldBe("NOT_FOUND", "Удаляемая запись отсутствует")
        }
    }

    /**
     * TAR-CRUD-008-API. POST с одинаковыми границами 100.00 возвращает VALIDATION_ERROR/400. Типизированные списки своего города до и после должны совпасть; SQL не используется.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-008-API")
    @Test
    @DisplayName("TAR-CRUD-008-API: неверное создание ничего не сохраняет")
    fun rejectsInvalidCreate() {

        val before = step("Читаем правила своего города до неверного создания") {
            tariffService.getRules().items.filter { it.cityId == cityId }
        }
        val invalidRequest = creationRequest.copy(lowerBound = "100.00", upperBound = "100.00")
        val error = step("Создаём правило с равными границами 100.00 и ожидаем HTTP 400") {
            tariffService.createRule(invalidRequest, expectedStatus = 400).`as`(ApiError::class.java)
        }
        step("Проверяем отказ создания правила с пустым диапазоном") {
            error.status.shouldBe(400, "Статус неверного POST")
            error.code.shouldBe("VALIDATION_ERROR", "Причина отказа создания")
        }
        val after = step("Читаем правила своего города после отклонённого POST") {
            tariffService.getRules().items.filter { it.cityId == cityId }
        }
        step("Проверяем отсутствие новых записей") {
            before.shouldBeEmpty()
            after.shouldBe(before, "Список правил города не изменился")
        }
    }

    /**
     * TAR-CRUD-009-API. Для правила версии 1 отправляем PUT с равными границами 100.00. Ожидаем VALIDATION_ERROR/400; GET должен сохранить все исходные поля и версию.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-009-API")
    @Test
    @DisplayName("TAR-CRUD-009-API: неверная замена сохраняет исходное правило")
    fun rejectsInvalidUpdate() {

        val originalRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        val invalidReplacement = creationRequest.copy(lowerBound = "100.00", upperBound = "100.00")
        val error = step("Заменяем существующее правило пустым диапазоном и ожидаем HTTP 400") {
            tariffService.updateRule(originalRule.tariffRuleId, invalidReplacement, expectedStatus = 400)
                .`as`(ApiError::class.java)
        }
        step("Проверяем отказ неверной замены") {
            error.status.shouldBe(400, "Статус неверного PUT")
            error.code.shouldBe("VALIDATION_ERROR", "Причина отказа замены")
        }
        val actualRule = step("Читаем исходное правило после отклонённого PUT") {
            tariffService.getRule(originalRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем сохранение всех исходных полей и версии") {
            actualRule.shouldBe(originalRule, "Неуспешная замена не меняет правило")
        }
    }

    /**
     * Общая подготовка существующего правила по образцу функций Template.
     * @param request поля создания, явно заданные data class
     * @return исходный Response POST с проверенным HTTP 201; бизнес-проверки остаются в сценарии
     * Побочный эффект: создание одной записи через API. Её UUID регистрируется для адресного удаления после теста.
     */
    private fun createRuleTemplate(request: RuleInput): Response {
        return step("Создаём исходное правило в собственном городе") {
            val response = tariffService.createRule(request)
            val rule = response.`as`(TariffRule::class.java)
            createdRuleIds.add(rule.tariffRuleId)
            response
        }
    }
}
