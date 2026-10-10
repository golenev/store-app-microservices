package org.golenev.tests.backend.whitebox

import io.kotest.matchers.collections.shouldBeEmpty
import io.qameta.allure.AllureId
import org.golenev.commondto.ApiError
import org.golenev.commondto.RuleInput
import org.golenev.commondto.TariffRule
import org.golenev.db.tables.tariffRules.TariffRuleRow
import org.golenev.db.tables.tariffRules.TariffRulesDao
import org.golenev.restapi.crud.endpoints.TariffCrudServiceDao
import org.golenev.utils.shouldBe
import org.golenev.utils.step
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Проверяет тарифное правило на границе HTTP и настоящей PostgreSQL развёрнутой TARIFFS.
 * Девять CRUD-сценариев сохраняют данные остальных уровней; ещё три проверяют прямые изменения БД и список.
 * Каждый вызов DAO завершается собственной транзакцией до запроса; общей откатываемой транзакции теста нет.
 * Подготовка данных, запись, воздействие, независимое чтение и проверки показаны отдельными шагами Allure.
 * Кеш котировок и Kafka не участвуют; очистка затрагивает только уникальный город текущего теста.
 */
@Tag("api-database")
@DisplayName("Тарифные правила: согласованность запросов и записей в базе данных")
class TariffCrudDatabaseApiTest {
    private val tariffService = TariffCrudServiceDao()
    private lateinit var cityId: String

    /** Выбирает отдельный город, чтобы подготовка и очистка не затронули правила других сценариев. */
    @BeforeEach
    fun prepareCity() {
        cityId = step("Выбираем отдельный город для тарифного правила и его независимой проверки в базе данных") {
            "WHT-" + UUID.randomUUID()
        }
    }

    /** Удаляет только правила своего уникального города; повторная очистка безопасна, включая уже удалённое правило. */
    @AfterEach
    fun cleanup() {
        step("Удаляем оставшиеся тарифные правила города $cityId после завершения сценария") {
            TariffRulesDao.deleteByCity(cityId)
        }
    }

    /**
     * TAR-CRUD-001-WHITE. Подготавливаем допустимые условия, создаём правило запросом и независимо читаем все восемь колонок.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-001-WHITE")
    @Test
    @DisplayName("Созданное тарифное правило сохраняется в базе данных с исходными условиями и версией 1")
    fun shouldCreateTariffRuleInDatabase() {
        val request = prepareInputTemplate()
        val createdRule = step("Создаём тарифное правило для непродовольственных товаров города $cityId с наценкой 20%") {
            tariffService.createRule(request).`as`(TariffRule::class.java)
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(UUID.fromString(createdRule.tariffRuleId))
        }
        step("Проверяем, что созданное тарифное правило ${createdRule.tariffRuleId} записано ровно один раз со всеми исходными условиями и версией 1") {
            rows.size.shouldBe(1, "Ровно одна строка созданного правила")
            val storedRule = rows.single()
            storedRule.tariffRuleId.shouldBe(UUID.fromString(createdRule.tariffRuleId), "Сохранённый идентификатор")
            storedRule.version.shouldBe(1L, "Версия сохранённых условий")
            storedRule.productType.shouldBe(request.productType, "Сохранённый тип товаров")
            storedRule.cityId.shouldBe(request.cityId, "Сохранённый город")
            storedRule.currency.shouldBe(request.currency, "Сохранённая валюта")
            storedRule.lowerBound.compareTo(request.lowerBound.toBigDecimal()).shouldBe(0, "Сохранённая нижняя граница")
            storedRule.upperBound.shouldBe(request.upperBound?.toBigDecimal(), "Сохранённый верхний предел")
            storedRule.markupRate.compareTo(request.markupRate.toBigDecimal()).shouldBe(0, "Сохранённая наценка")
            createdRule.tariffRuleId.shouldBe(storedRule.tariffRuleId.toString(), "Идентификатор строки и ответа")
            createdRule.version.shouldBe(storedRule.version, "Версия из базы данных")
            createdRule.productType.shouldBe(storedRule.productType, "Тип товаров")
            createdRule.cityId.shouldBe(storedRule.cityId, "Город действия наценки")
            createdRule.currency.shouldBe(storedRule.currency, "Валюта закупочной цены")
            createdRule.lowerBound.shouldBe(storedRule.lowerBound.toPlainString(), "Нижняя граница")
            createdRule.upperBound.shouldBe(storedRule.upperBound?.toPlainString(), "Верхняя граница и отсутствие предела")
            createdRule.markupRate.toBigDecimal().compareTo(storedRule.markupRate).shouldBe(0, "Точная наценка без влияния масштаба числа")
        }
    }

    /**
     * TAR-CRUD-002-WHITE. Подготавливаем и фиксируем строку напрямую. Чтение по идентификатору возвращает все условия и версию этой строки.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-002-WHITE")
    @Test
    @DisplayName("Тарифное правило, подготовленное в базе данных, читается с исходными условиями")
    fun shouldReadTariffRulePreparedInDatabase() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        val actualRule = step("Читаем тарифное правило ${originalRule.tariffRuleId} после фиксации его условий") {
            tariffService.getRule(originalRule.tariffRuleId.toString()).`as`(TariffRule::class.java)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} возвращается с условиями, подготовленными в базе данных") {
            actualRule.tariffRuleId.shouldBe(originalRule.tariffRuleId.toString(), "Идентификатор строки и ответа")
            actualRule.version.shouldBe(originalRule.version, "Версия из базы данных")
            actualRule.productType.shouldBe(originalRule.productType, "Тип товаров")
            actualRule.cityId.shouldBe(originalRule.cityId, "Город действия наценки")
            actualRule.currency.shouldBe(originalRule.currency, "Валюта закупочной цены")
            actualRule.lowerBound.shouldBe(originalRule.lowerBound.toPlainString(), "Нижняя граница")
            actualRule.upperBound.shouldBe(originalRule.upperBound?.toPlainString(), "Верхняя граница и отсутствие предела")
            actualRule.markupRate.toBigDecimal().compareTo(originalRule.markupRate).shouldBe(0, "Точная наценка без влияния масштаба числа")
        }
    }

    /**
     * TAR-CRUD-003-WHITE. Создаём строку напрямую, заменяем условия запросом и проверяем все поля строки и ответа, включая снятый верхний предел.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-003-WHITE")
    @Test
    @DisplayName("Замена тарифного правила сохраняет новые условия в базе данных и увеличивает версию до 2")
    fun shouldReplaceTariffRuleInDatabase() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        val replacement = step("Подготавливаем новые условия тарифного правила: продовольственные товары города $cityId, закупочная цена от 5.00 рублей без верхнего предела, наценка 30%") {
            RuleInput(
                productType = "FOOD", cityId = cityId, currency = "RUB",
                lowerBound = "5.00", upperBound = null, markupRate = "0.30",
            )
        }
        val updatedRule = step("Заменяем условия тарифного правила ${originalRule.tariffRuleId} на наценку 30% для продовольственных товаров") {
            tariffService.updateRule(originalRule.tariffRuleId.toString(), replacement).`as`(TariffRule::class.java)
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(originalRule.tariffRuleId)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} сохранило идентификатор, получило новые условия и версию 2 в базе данных") {
            rows.size.shouldBe(1, "Ровно одна строка после замены")
            val storedRule = rows.single()
            storedRule.tariffRuleId.shouldBe(UUID.fromString(originalRule.tariffRuleId.toString()), "Сохранённый идентификатор")
            storedRule.version.shouldBe(2L, "Версия сохранённых условий")
            storedRule.productType.shouldBe(replacement.productType, "Сохранённый тип товаров")
            storedRule.cityId.shouldBe(replacement.cityId, "Сохранённый город")
            storedRule.currency.shouldBe(replacement.currency, "Сохранённая валюта")
            storedRule.lowerBound.compareTo(replacement.lowerBound.toBigDecimal()).shouldBe(0, "Сохранённая нижняя граница")
            storedRule.upperBound.shouldBe(replacement.upperBound?.toBigDecimal(), "Сохранённый верхний предел")
            storedRule.markupRate.compareTo(replacement.markupRate.toBigDecimal()).shouldBe(0, "Сохранённая наценка")
            updatedRule.tariffRuleId.shouldBe(storedRule.tariffRuleId.toString(), "Идентификатор строки и ответа")
            updatedRule.version.shouldBe(storedRule.version, "Версия из базы данных")
            updatedRule.productType.shouldBe(storedRule.productType, "Тип товаров")
            updatedRule.cityId.shouldBe(storedRule.cityId, "Город действия наценки")
            updatedRule.currency.shouldBe(storedRule.currency, "Валюта закупочной цены")
            updatedRule.lowerBound.shouldBe(storedRule.lowerBound.toPlainString(), "Нижняя граница")
            updatedRule.upperBound.shouldBe(storedRule.upperBound?.toPlainString(), "Верхняя граница и отсутствие предела")
            updatedRule.markupRate.toBigDecimal().compareTo(storedRule.markupRate).shouldBe(0, "Точная наценка без влияния масштаба числа")
        }
    }

    /**
     * TAR-CRUD-004-WHITE. Фиксируем исходную строку, удаляем запросом; независимое чтение БД и запрос подтверждают отсутствие.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-004-WHITE")
    @Test
    @DisplayName("Удаление тарифного правила убирает строку из базы данных и делает правило недоступным")
    fun shouldDeleteTariffRuleFromDatabase() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        step("Удаляем тарифное правило ${originalRule.tariffRuleId}, подготовленное в базе данных") {
            tariffService.deleteRule(originalRule.tariffRuleId.toString())
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(originalRule.tariffRuleId)
        }
        step("Проверяем, что удалённое тарифное правило ${originalRule.tariffRuleId} отсутствует в базе данных") {
            rows.shouldBeEmpty()
        }
        val error = step("Запрашиваем тарифное правило ${originalRule.tariffRuleId} после удаления") {
            tariffService.getRule(originalRule.tariffRuleId.toString(), expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем, что удалённое тарифное правило ${originalRule.tariffRuleId} больше не доступно") {
            error.status.shouldBe(404, "Статус отказа")
            error.code.shouldBe("NOT_FOUND", "Причина отказа")
        }
    }

    /**
     * TAR-CRUD-005-WHITE. Подтверждаем отсутствие строки до запроса и после отказа; допустимая замена отсутствующей записи не должна создавать новую.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-005-WHITE")
    @Test
    @DisplayName("Чтение отсутствующего тарифного правила возвращает отказ и не создаёт строку")
    fun shouldRejectReadingMissingTariffRule() {
        val ruleId = step("Подготавливаем идентификатор тарифного правила, которое не было создано") {
            UUID.randomUUID()
        }
        val before = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(ruleId)
        }
        step("Проверяем исходное состояние: тарифного правила $ruleId нет в базе данных") {
            before.shouldBeEmpty()
        }
        val error = step("Пытаемся прочитать тарифное правило $ruleId, которое отсутствует в сохранённых правилах") {
            tariffService.getRule(ruleId.toString(), expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ для тарифного правила $ruleId: правило не найдено") {
            error.status.shouldBe(404, "Статус отказа")
            error.code.shouldBe("NOT_FOUND", "Причина отказа")
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(ruleId)
        }
        step("Проверяем, что отказ не создал тарифное правило $ruleId в базе данных") {
            rows.shouldBeEmpty()
        }
    }

    /**
     * TAR-CRUD-006-WHITE. Подтверждаем отсутствие строки до запроса и после отказа; допустимая замена отсутствующей записи не должна создавать новую.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-006-WHITE")
    @Test
    @DisplayName("Замена отсутствующего тарифного правила возвращает отказ и не создаёт строку")
    fun shouldRejectReplacingMissingTariffRule() {
        val ruleId = step("Подготавливаем идентификатор тарифного правила, которое не было создано") {
            UUID.randomUUID()
        }
        val before = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(ruleId)
        }
        step("Проверяем исходное состояние: тарифного правила $ruleId нет в базе данных") {
            before.shouldBeEmpty()
        }
        val replacement = step("Подготавливаем новые условия тарифного правила: продовольственные товары города $cityId, закупочная цена от 5.00 рублей без верхнего предела, наценка 30%") {
            RuleInput(
                productType = "FOOD", cityId = cityId, currency = "RUB",
                lowerBound = "5.00", upperBound = null, markupRate = "0.30",
            )
        }
        val error = step("Пытаемся заменить условия тарифного правила $ruleId, которое отсутствует в сохранённых правилах") {
            tariffService.updateRule(ruleId.toString(), replacement, expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ для тарифного правила $ruleId: правило не найдено") {
            error.status.shouldBe(404, "Статус отказа")
            error.code.shouldBe("NOT_FOUND", "Причина отказа")
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(ruleId)
        }
        step("Проверяем, что отказ не создал тарифное правило $ruleId в базе данных") {
            rows.shouldBeEmpty()
        }
        val readError = step("Читаем тарифное правило $ruleId после отклонённой замены") {
            tariffService.getRule(ruleId.toString(), expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем, что тарифное правило $ruleId по-прежнему отсутствует") {
            readError.status.shouldBe(404, "Статус отказа")
            readError.code.shouldBe("NOT_FOUND", "Причина отказа")
        }
    }

    /**
     * TAR-CRUD-007-WHITE. Подтверждаем отсутствие строки до запроса и после отказа; допустимая замена отсутствующей записи не должна создавать новую.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-007-WHITE")
    @Test
    @DisplayName("Удаление отсутствующего тарифного правила возвращает отказ и не меняет базу данных")
    fun shouldRejectDeletingMissingTariffRule() {
        val ruleId = step("Подготавливаем идентификатор тарифного правила, которое не было создано") {
            UUID.randomUUID()
        }
        val before = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(ruleId)
        }
        step("Проверяем исходное состояние: тарифного правила $ruleId нет в базе данных") {
            before.shouldBeEmpty()
        }
        val error = step("Пытаемся удалить тарифное правило $ruleId, которое отсутствует в сохранённых правилах") {
            tariffService.deleteRule(ruleId.toString(), expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем отказ для тарифного правила $ruleId: правило не найдено") {
            error.status.shouldBe(404, "Статус отказа")
            error.code.shouldBe("NOT_FOUND", "Причина отказа")
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(ruleId)
        }
        step("Проверяем, что отказ не создал тарифное правило $ruleId в базе данных") {
            rows.shouldBeEmpty()
        }
    }

    /**
     * TAR-CRUD-008-WHITE. Передаём равные границы 100.00; проверяем структурированный отказ и неизменность строк своего города.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-008-WHITE")
    @Test
    @DisplayName("Тарифное правило с пустым диапазоном отклоняется и не добавляет строку в базу данных")
    fun shouldRejectCreatingInvalidTariffRule() {
        val request = prepareInputTemplate()
        val before = step("Читаем исходные тарифные правила города $cityId в базе данных") {
            TariffRulesDao.findByCity(cityId)
        }
        step("Проверяем, что тарифное правило для города $cityId ещё не создано") {
            before.shouldBeEmpty()
        }
        val invalidRequest = step("Подготавливаем недопустимое создание тарифного правила: обе границы закупочной цены равны 100.00 рублей, остальные условия сохраняем") {
            request.copy(lowerBound = "100.00", upperBound = "100.00")
        }
        val error = step("Пытаемся создать тарифное правило для города $cityId с пустым диапазоном закупочной цены") {
            tariffService.createRule(invalidRequest, expectedStatus = 400).`as`(ApiError::class.java)
        }
        step("Проверяем отказ в создании тарифного правила с пустым диапазоном") {
            error.status.shouldBe(400, "Статус отказа")
            error.code.shouldBe("VALIDATION_ERROR", "Причина отказа")
        }
        val after = step("Читаем тарифные правила города $cityId в базе данных после отказа") {
            TariffRulesDao.findByCity(cityId)
        }
        step("Проверяем, что отклонённое тарифное правило не появилось в городе $cityId") {
            after.shouldBe(before, "Отказ не добавляет строку в базу данных")
        }
    }

    /**
     * TAR-CRUD-009-WHITE. После недопустимой замены сверяем полный снимок строки и результат чтения; исходная версия остаётся равной 1.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-009-WHITE")
    @Test
    @DisplayName("Отказ в замене тарифного правила сохраняет все исходные колонки и версию 1")
    fun shouldPreserveTariffRuleAfterRejectedReplacement() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        val invalidReplacement = step("Подготавливаем недопустимую замену тарифного правила ${originalRule.tariffRuleId}: обе границы равны 100.00 рублей, город, тип товаров и наценку 20% сохраняем") {
            RuleInput(
                productType = originalRule.productType, cityId = cityId, currency = originalRule.currency,
                lowerBound = "100.00", upperBound = "100.00", markupRate = "0.20",
            )
        }
        val error = step("Пытаемся заменить тарифное правило ${originalRule.tariffRuleId} пустым диапазоном") {
            tariffService.updateRule(originalRule.tariffRuleId.toString(), invalidReplacement, expectedStatus = 400).`as`(ApiError::class.java)
        }
        step("Проверяем отказ в замене тарифного правила ${originalRule.tariffRuleId}") {
            error.status.shouldBe(400, "Статус отказа")
            error.code.shouldBe("VALIDATION_ERROR", "Причина отказа")
        }
        val rows = step("Читаем сохранённое состояние тарифного правила в базе данных города $cityId") {
            TariffRulesDao.findById(originalRule.tariffRuleId)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} осталось в базе данных без изменения условий и версии") {
            rows.shouldBe(listOf(originalRule), "Все восемь колонок исходной строки сохранены")
        }
        val actualRule = step("Читаем тарифное правило ${originalRule.tariffRuleId} после фиксации его условий") {
            tariffService.getRule(originalRule.tariffRuleId.toString()).`as`(TariffRule::class.java)
        }
        step("Проверяем, что тарифное правило ${originalRule.tariffRuleId} читается с исходными условиями после отказа") {
            actualRule.tariffRuleId.shouldBe(originalRule.tariffRuleId.toString(), "Идентификатор строки и ответа")
            actualRule.version.shouldBe(originalRule.version, "Версия из базы данных")
            actualRule.productType.shouldBe(originalRule.productType, "Тип товаров")
            actualRule.cityId.shouldBe(originalRule.cityId, "Город действия наценки")
            actualRule.currency.shouldBe(originalRule.currency, "Валюта закупочной цены")
            actualRule.lowerBound.shouldBe(originalRule.lowerBound.toPlainString(), "Нижняя граница")
            actualRule.upperBound.shouldBe(originalRule.upperBound?.toPlainString(), "Верхняя граница и отсутствие предела")
            actualRule.markupRate.toBigDecimal().compareTo(originalRule.markupRate).shouldBe(0, "Точная наценка без влияния масштаба числа")
        }
    }

    /**
     * TAR-CRUD-010-WHITE. Обратный путь: фиксируем новые поля и версию непосредственно в БД; последующий запрос читает все новые значения, включая null.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-010-WHITE")
    @Test
    @DisplayName("Прямое изменение тарифного правила в базе данных становится видимым при чтении")
    fun shouldReadTariffRuleAfterDirectDatabaseReplacement() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        val replacementRow = step("Подготавливаем новые сохранённые условия тарифного правила ${originalRule.tariffRuleId}: версия 2, продовольственные товары, закупочная цена от 5.00 рублей без верхнего предела и наценка 30%") {
            originalRule.copy(
                version = 2L, productType = "FOOD", lowerBound = BigDecimal("5.00"),
                upperBound = null, markupRate = BigDecimal("0.300000"),
            )
        }
        val changedRows = step("Сохраняем новые условия тарифного правила ${originalRule.tariffRuleId} напрямую") {
            TariffRulesDao.replace(replacementRow)
        }
        step("Проверяем, что изменено только тарифное правило ${originalRule.tariffRuleId}") {
            changedRows.shouldBe(1, "Изменена ровно одна строка")
        }
        val actualRule = step("Читаем тарифное правило ${originalRule.tariffRuleId} после фиксации его условий") {
            tariffService.getRule(originalRule.tariffRuleId.toString()).`as`(TariffRule::class.java)
        }
        step("Проверяем, что чтение тарифного правила ${originalRule.tariffRuleId} возвращает новые сохранённые условия и версию 2") {
            actualRule.tariffRuleId.shouldBe(replacementRow.tariffRuleId.toString(), "Идентификатор строки и ответа")
            actualRule.version.shouldBe(replacementRow.version, "Версия из базы данных")
            actualRule.productType.shouldBe(replacementRow.productType, "Тип товаров")
            actualRule.cityId.shouldBe(replacementRow.cityId, "Город действия наценки")
            actualRule.currency.shouldBe(replacementRow.currency, "Валюта закупочной цены")
            actualRule.lowerBound.shouldBe(replacementRow.lowerBound.toPlainString(), "Нижняя граница")
            actualRule.upperBound.shouldBe(replacementRow.upperBound?.toPlainString(), "Верхняя граница и отсутствие предела")
            actualRule.markupRate.toBigDecimal().compareTo(replacementRow.markupRate).shouldBe(0, "Точная наценка без влияния масштаба числа")
        }
    }

    /**
     * TAR-CRUD-011-WHITE. Обратный путь: удаляем строку в БД и фиксируем транзакцию; запрос возвращает NOT_FOUND вместо старой записи.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-011-WHITE")
    @Test
    @DisplayName("Прямое удаление тарифного правила из базы данных делает его недоступным для чтения")
    fun shouldRejectReadingTariffRuleDeletedDirectly() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        val deletedRows = step("Удаляем тарифное правило ${originalRule.tariffRuleId} непосредственно из сохранённых правил") {
            TariffRulesDao.deleteById(originalRule.tariffRuleId)
        }
        step("Проверяем, что удалено только тарифное правило ${originalRule.tariffRuleId}") {
            deletedRows.shouldBe(1, "Удалена ровно одна строка")
        }
        val error = step("Запрашиваем тарифное правило ${originalRule.tariffRuleId} после прямого удаления") {
            tariffService.getRule(originalRule.tariffRuleId.toString(), expectedStatus = 404).`as`(ApiError::class.java)
        }
        step("Проверяем, что удалённое тарифное правило ${originalRule.tariffRuleId} больше не доступно") {
            error.status.shouldBe(404, "Статус отказа")
            error.code.shouldBe("NOT_FOUND", "Причина отказа")
        }
    }

    /**
     * TAR-CRUD-012-WHITE. Фиксируем одну строку и читаем общий список; проверка ограничена своим городом и не зависит от чужих правил.
     * Данные изолированы городом; последующее чтение выполняется после фиксации транзакции.
     */
    @AllureId("TAR-CRUD-012-WHITE")
    @Test
    @DisplayName("Список тарифных правил содержит запись, подготовленную непосредственно в базе данных")
    fun shouldListTariffRulePreparedInDatabase() {
        val originalRule = prepareRowTemplate()
        step("Сохраняем тарифное правило ${originalRule.tariffRuleId} версии 1 для непродовольственных товаров города $cityId") {
            TariffRulesDao.insert(originalRule)
        }
        val rules = step("Читаем список тарифных правил и выбираем правила своего города $cityId") {
            tariffService.getRules().items.filter { it.cityId == cityId }
        }
        step("Проверяем, что список содержит только подготовленное тарифное правило города $cityId со всеми сохранёнными условиями") {
            rules.size.shouldBe(1, "Ровно одно правило своего города")
            val actualRule = rules.single()
            actualRule.tariffRuleId.shouldBe(originalRule.tariffRuleId.toString(), "Идентификатор строки и ответа")
            actualRule.version.shouldBe(originalRule.version, "Версия из базы данных")
            actualRule.productType.shouldBe(originalRule.productType, "Тип товаров")
            actualRule.cityId.shouldBe(originalRule.cityId, "Город действия наценки")
            actualRule.currency.shouldBe(originalRule.currency, "Валюта закупочной цены")
            actualRule.lowerBound.shouldBe(originalRule.lowerBound.toPlainString(), "Нижняя граница")
            actualRule.upperBound.shouldBe(originalRule.upperBound?.toPlainString(), "Верхняя граница и отсутствие предела")
            actualRule.markupRate.toBigDecimal().compareTo(originalRule.markupRate).shouldBe(0, "Точная наценка без влияния масштаба числа")
        }
    }

    /**
     * Подготавливает допустимые исходные условия без записи в приложение.
     * @return непродовольственные товары своего города, диапазон 0.00–500.00 рублей и наценка 20%
     */
    private fun prepareInputTemplate(): RuleInput {
        return step("Подготавливаем создание тарифного правила для непродовольственных товаров города $cityId: закупочная цена 0.00–500.00 рублей, наценка 20%; запись ещё не создана") {
            RuleInput(
                productType = "NON_FOOD", cityId = cityId, currency = "RUB",
                lowerBound = "0.00", upperBound = "500.00", markupRate = "0.20",
            )
        }
    }

    /**
     * Подготавливает полный снимок исходной строки без обращения к БД.
     * @return свой идентификатор, версия 1 и те же условия, что в остальных CRUD-наборах; масштаб чисел соответствует SQL
     */
    private fun prepareRowTemplate(): TariffRuleRow {
        return step("Подготавливаем строку тарифного правила для непродовольственных товаров города $cityId: собственный идентификатор, версия 1, закупочная цена 0.00–500.00 рублей и наценка 20%; запись ещё не сохранена") {
            TariffRuleRow(
                tariffRuleId = UUID.randomUUID(), version = 1L,
                productType = "NON_FOOD", cityId = cityId, currency = "RUB",
                lowerBound = BigDecimal("0.00"), upperBound = BigDecimal("500.00"),
                markupRate = BigDecimal("0.200000"),
            )
        }
    }
}
