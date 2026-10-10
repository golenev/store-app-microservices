package org.golenev.tests.backend.blackbox

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Девять прежних CRUD-сценариев чёрного ящика по структуре backend-тестов golenev-xlsx-report-system.
 * Субъект каждого сценария — тарифное правило: условия наценки для товаров одного города.
 * Шаги показывают подготовку конкретных условий, создание, переход условий, чтение состояния
 * и итог принятой либо отклонённой операции. Подготовка данных сама по себе не меняет сохранённое правило.
 * Внешнее состояние читается только через HTTP. Каждый сценарий получает собственный город.
 */
@Tag("api")
@DisplayName("Тарифные правила: жизненный цикл условий наценки для товаров города")
class TariffCrudApiTest {
    private val tariffService = TariffCrudServiceDao()
    private lateinit var cityId: String
    private val createdRuleIds = mutableSetOf<String>()

    /** Выбирает отдельный город для изоляции тарифного правила; общие данные приложения не изменяются. */
    @BeforeEach
    fun prepareCity() {
        cityId = step("Выбираем отдельный город для тарифного правила, чтобы условия наценки не пересекались с другими сценариями") {
            "PYR-" + UUID.randomUUID()
        }
    }

    /**
     * Удаляет только UUID, успешно созданные текущим сценарием, через публичный DELETE.
     * Уже удалённое проверкой правило исключается из набора; чужие правила и общий кеш не затрагиваются.
     */
    @AfterEach
    fun cleanup() {
        createdRuleIds.forEach { ruleId ->
            step("Удаляем созданное тарифное правило $ruleId для города $cityId после завершения сценария") {
                tariffService.deleteRule(ruleId)
            }
        }
        createdRuleIds.clear()
    }

    /**
     * TAR-CRUD-001-API. Создаём NON_FOOD/RUB, диапазон 0.00–500.00 и ставку 0.20. Проверяем 201, Location, UUID, все поля версии 1 и сохранение через GET.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-001-API")
    @Test
    @DisplayName("Тарифное правило для непродовольственных товаров создаётся с наценкой 20% и сохраняется в версии 1")
    fun shouldCreateTariffRuleWithVersionOne() {
        val creationRequest = prepareRuleTemplate()
        val response = createRuleTemplate(creationRequest)
        val createdRule = response.`as`(TariffRule::class.java)
        step("Проверяем, что тарифное правило ${createdRule.tariffRuleId} получило идентификатор, адрес и версию 1 с заданными условиями наценки") {
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
        val savedRule = step("Читаем сохранённое тарифное правило ${createdRule.tariffRuleId} для города $cityId") {
            tariffService.getRule(createdRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем, что сохранённое тарифное правило ${createdRule.tariffRuleId} содержит исходные условия наценки и версию 1") {
            savedRule.shouldBe(createdRule, "GET возвращает созданное правило")
        }
    }

    /**
     * TAR-CRUD-002-API. Подготавливаем правило версии 1. GET по UUID должен вернуть все исходные поля без изменений; сравниваем типизированные ответы целиком.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-002-API")
    @Test
    @DisplayName("Созданное тарифное правило возвращается с исходными условиями наценки и версией 1")
    fun shouldReadTariffRuleWithOriginalTerms() {
        val creationRequest = prepareRuleTemplate()
        val createdRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        val actualRule = step("Читаем тарифное правило ${createdRule.tariffRuleId}, созданное для непродовольственных товаров города $cityId") {
            tariffService.getRule(createdRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем, что чтение тарифного правила ${createdRule.tariffRuleId} возвращает все исходные условия и версию 1") {
            actualRule.shouldBe(createdRule, "Ответ GET совпадает с ответом POST")
        }
    }

    /**
     * TAR-CRUD-003-API. Версию 1 заменяем на FOOD, нижнюю границу 5.00, null верхнего предела и ставку 0.30. Проверяем прежний UUID, version=2, все новые поля и их сохранение через GET.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-003-API")
    @Test
    @DisplayName("Тарифное правило меняет условия на наценку 30% для продовольственных товаров и сохраняется в версии 2")
    fun shouldReplaceTariffRuleTermsWithVersionTwo() {
        val creationRequest = prepareRuleTemplate()
        val originalRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        val replacement = step("Подготавливаем новые условия тарифного правила: продовольственные товары города $cityId, закупочная цена от 5.00 рублей без верхнего предела, наценка 30%") {
            RuleInput(
                productType = "FOOD", cityId = cityId, currency = "RUB",
                lowerBound = "5.00", upperBound = null, markupRate = "0.30",
            )
        }
        val updatedRule = step("Заменяем условия тарифного правила ${originalRule.tariffRuleId}: продовольственные товары, закупочная цена от 5.00 рублей без верхнего предела, наценка 30%") {
            tariffService.updateRule(originalRule.tariffRuleId, replacement).`as`(TariffRule::class.java)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} сохранило идентификатор и получило новые условия наценки в версии 2") {
            updatedRule.tariffRuleId.shouldBe(originalRule.tariffRuleId, "UUID сохраняется")
            updatedRule.version.shouldBe(2L, "Версия увеличивается ровно на один")
            updatedRule.productType.shouldBe(replacement.productType, "Новый тип товара")
            updatedRule.cityId.shouldBe(replacement.cityId, "Город после замены")
            updatedRule.currency.shouldBe(replacement.currency, "Валюта после замены")
            updatedRule.lowerBound.shouldBe(replacement.lowerBound, "Новая нижняя граница")
            updatedRule.upperBound.shouldBe(replacement.upperBound, "Верхний предел снят")
            updatedRule.markupRate.shouldBe(replacement.markupRate, "Новая ставка")
        }
        val savedRule = step("Читаем тарифное правило ${originalRule.tariffRuleId} после замены условий наценки") {
            tariffService.getRule(originalRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} сохранило условия для продовольственных товаров и версию 2") {
            savedRule.shouldBe(updatedRule, "GET возвращает результат PUT")
        }
    }

    /**
     * TAR-CRUD-004-API. Создаём и удаляем правило с HTTP 204. Следующий GET должен вернуть NOT_FOUND/404; ApiError проверяется внутри сценария.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-004-API")
    @Test
    @DisplayName("Созданное тарифное правило удаляется и становится недоступным для чтения")
    fun shouldDeleteCreatedTariffRule() {
        val creationRequest = prepareRuleTemplate()
        val createdRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        step("Удаляем тарифное правило ${createdRule.tariffRuleId}, созданное для города $cityId") {
            tariffService.deleteRule(createdRule.tariffRuleId)
            createdRuleIds.remove(createdRule.tariffRuleId)
        }
        val error = step("Запрашиваем тарифное правило ${createdRule.tariffRuleId} после его удаления") {
            tariffService.getRule(createdRule.tariffRuleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем, что удалённое тарифное правило ${createdRule.tariffRuleId} больше не доступно: правило не найдено") {
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
    @DisplayName("Чтение отсутствующего тарифного правила завершается отказом: правило не найдено")
    fun shouldRejectReadingMissingTariffRule() {
        val ruleId = step("Выбираем идентификатор тарифного правила, которое не было создано") {
            UUID.randomUUID().toString()
        }
        val error = step("Запрашиваем тарифное правило $ruleId, которое не было создано") {
            tariffService.getRule(ruleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ в чтении тарифного правила $ruleId: правило не найдено") {
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
    @DisplayName("Замена условий отсутствующего тарифного правила отклоняется и не создаёт правило")
    fun shouldRejectReplacingMissingTariffRuleWithoutCreatingIt() {
        val ruleId = step("Выбираем идентификатор тарифного правила, которое не было создано") {
            UUID.randomUUID().toString()
        }
        val replacement = step("Подготавливаем новые условия тарифного правила: продовольственные товары города $cityId, закупочная цена от 5.00 рублей без верхнего предела, наценка 30%") {
            RuleInput(
                productType = "FOOD", cityId = cityId, currency = "RUB",
                lowerBound = "5.00", upperBound = null, markupRate = "0.30",
            )
        }
        val updateError = step("Пытаемся задать отсутствующему тарифному правилу $ruleId наценку 30% для продовольственных товаров с закупочной ценой от 5.00 рублей") {
            tariffService.updateRule(ruleId, replacement, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ в замене условий тарифного правила $ruleId: правило не найдено") {
            updateError.status.shouldBe(404, "Статус отказа PUT")
            updateError.code.shouldBe("NOT_FOUND", "Причина отказа PUT")
        }
        val readError = step("Запрашиваем тарифное правило $ruleId после отклонённой замены условий") {
            tariffService.getRule(ruleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем, что отклонённая замена не создала тарифное правило $ruleId") {
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
    @DisplayName("Удаление отсутствующего тарифного правила завершается отказом: правило не найдено")
    fun shouldRejectDeletingMissingTariffRule() {
        val ruleId = step("Выбираем идентификатор тарифного правила, которое не было создано") {
            UUID.randomUUID().toString()
        }
        val error = step("Пытаемся удалить тарифное правило $ruleId, которое не было создано") {
            tariffService.deleteRule(ruleId, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ в удалении тарифного правила $ruleId: правило не найдено") {
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
    @DisplayName("Тарифное правило с равными границами закупочной цены отклоняется и не появляется в городе")
    fun shouldRejectCreatingTariffRuleWithEmptyPriceRange() {
        val creationRequest = prepareRuleTemplate()
        val before = step("Проверяем исходное состояние: в городе $cityId нет тарифного правила для непродовольственных товаров") {
            tariffService.getRules().items.filter { it.cityId == cityId }.also { it.shouldBeEmpty() }
        }
        val invalidRequest = step("Подготавливаем недопустимые условия создания тарифного правила: обе границы закупочной цены равны 100.00 рублей и образуют пустой диапазон; непродовольственные товары, город $cityId и наценку 20% сохраняем") {
            creationRequest.copy(lowerBound = "100.00", upperBound = "100.00")
        }
        val error = step("Пытаемся создать тарифное правило для непродовольственных товаров города $cityId с наценкой 20% и обеими границами закупочной цены 100.00 рублей") {
            tariffService.createRule(invalidRequest, expectedStatus = 400).`as`(ApiError::class.java)
        }
        step("Проверяем отказ в создании тарифного правила города $cityId: равные границы закупочной цены образуют пустой диапазон") {
            error.status.shouldBe(400, "Статус неверного POST")
            error.code.shouldBe("VALIDATION_ERROR", "Причина отказа создания")
        }
        val after = step("Читаем тарифные правила города $cityId после отказа в создании правила с пустым диапазоном") {
            tariffService.getRules().items.filter { it.cityId == cityId }
        }
        step("Проверяем, что после отказа тарифное правило с пустым диапазоном не появилось в городе $cityId") {
            after.shouldBe(before, "Список правил города не изменился")
        }
    }

    /**
     * TAR-CRUD-009-API. Для правила версии 1 отправляем PUT с равными границами 100.00. Ожидаем VALIDATION_ERROR/400; GET должен сохранить все исходные поля и версию.
     * Данные и ожидаемое правило совпадают с одноимёнными сценариями Mockito, WireMock и PostgreSQL.
     */
    @AllureId("TAR-CRUD-009-API")
    @Test
    @DisplayName("После отказа в замене на пустой диапазон тарифное правило сохраняет исходные условия и версию 1")
    fun shouldPreserveTariffRuleAfterRejectedReplacement() {
        val creationRequest = prepareRuleTemplate()
        val originalRule = createRuleTemplate(creationRequest).`as`(TariffRule::class.java)
        val invalidReplacement = step("Подготавливаем недопустимую замену тарифного правила ${originalRule.tariffRuleId}: обе границы закупочной цены задаём равными 100.00 рублей; остальные условия оставляем исходными, чтобы проверить отказ из-за пустого диапазона") {
            creationRequest.copy(lowerBound = "100.00", upperBound = "100.00")
        }
        val error = step("Пытаемся заменить диапазон закупочной цены тарифного правила ${originalRule.tariffRuleId} на равные границы 100.00 рублей") {
            tariffService.updateRule(originalRule.tariffRuleId, invalidReplacement, expectedStatus = 400)
                .`as`(ApiError::class.java)
        }
        step("Проверяем отказ в замене тарифного правила ${originalRule.tariffRuleId}: новый диапазон закупочной цены пустой") {
            error.status.shouldBe(400, "Статус неверного PUT")
            error.code.shouldBe("VALIDATION_ERROR", "Причина отказа замены")
        }
        val actualRule = step("Читаем тарифное правило ${originalRule.tariffRuleId} после отказа в замене диапазона закупочной цены") {
            tariffService.getRule(originalRule.tariffRuleId).`as`(TariffRule::class.java)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} осталось в версии 1 с наценкой 20% и исходным диапазоном 0.00–500.00 рублей") {
            actualRule.shouldBe(originalRule, "Неуспешная замена не меняет правило")
        }
    }

    /**
     * Подготавливает исходные условия тарифного правила для сценариев создания и изменения.
     * Запись ещё не существует: функция только задаёт непродовольственные товары, рубли,
     * диапазон закупочной цены 0.00–500.00 и наценку 20% для отдельного города текущего теста.
     * @return типизированные условия создания; обращений к приложению и сохранения данных нет
     */
    private fun prepareRuleTemplate(): RuleInput {
        return step("Подготавливаем условия тарифного правила для непродовольственных товаров города $cityId: закупочная цена 0.00–500.00 рублей, наценка 20%; правило ещё не создано") {
            RuleInput(
                productType = "NON_FOOD", cityId = cityId, currency = "RUB",
                lowerBound = "0.00", upperBound = "500.00", markupRate = "0.20",
            )
        }
    }

    /**
     * Общая подготовка существующего правила по образцу функций Template.
     * @param request поля создания, явно заданные data class
     * @return исходный Response POST с проверенным HTTP 201; бизнес-проверки остаются в сценарии
     * Побочный эффект: создание одной записи через API. Её UUID регистрируется для адресного удаления после теста.
     */
    private fun createRuleTemplate(request: RuleInput): Response {
        return step("Создаём тарифное правило для непродовольственных товаров города ${request.cityId}: закупочная цена 0.00–500.00 рублей, наценка 20%") {
            val response = tariffService.createRule(request)
            val rule = response.`as`(TariffRule::class.java)
            createdRuleIds.add(rule.tariffRuleId)
            response
        }
    }
}
