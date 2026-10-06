package org.golenev.tests.e2e_tests

import com.codeborne.selenide.Selenide
import com.codeborne.selenide.WebDriverRunner.getSelenideProxy
import com.codeborne.selenide.logevents.LogEventListener
import com.codeborne.selenide.logevents.SelenideLogger
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.qameta.allure.AllureId
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.golenev.commondto.CartLine
import org.golenev.commondto.OrderSubmitted
import org.golenev.commondto.Submission
import org.golenev.config.Environment
import org.golenev.ui.allure.UiElementNameRegistry
import org.golenev.ui.config.DriverConfig
import org.golenev.ui.config.interceptResponseBody
import org.golenev.ui.pages.catalogPage
import org.golenev.ui.pages.supplierPage
import org.golenev.utils.JsonUtils
import org.golenev.utils.kafka.consumer.ConsumedMessage
import org.golenev.utils.kafka.consumer.ConsumerKafkaConfig
import org.golenev.utils.kafka.consumer.runService
import org.golenev.utils.step
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Чёрный ящик: все поставки и покупки выполняются браузером, конечное событие читается из брокера без обращения к БД. */
@Epic("Учебный магазин")
@Feature("Поставка и заявка через браузер до сообщения получателю")
@DisplayName("Поставка через форму и оформление заявки с проверкой сообщения получателю")
class UiOrderKafkaE2ETest {
    private val storeId = "S-1"
    private val topic = "store.order-submitted"
    private val consumer = runService<OrderSubmitted>(ConsumerKafkaConfig(Environment.KAFKA_BOOTSTRAP, topic)) {
        it.payload.submissionId
    }

    /** Настраивает браузер и фиксирует начало чтения до первой поставки; каждому тесту принадлежит отдельный consumer. */
    @BeforeEach
    fun setUp() {
        DriverConfig().setup()
        consumer.start()
    }

    /** Закрывает браузер и consumer даже при падении сценария; созданные бизнес-данные остаются согласно правилам проекта. */
    @AfterEach
    fun tearDown() {
        try {
            Selenide.closeWebDriver()
        } finally {
            try {
                consumer.close()
            } finally {
                SelenideLogger.removeListener<LogEventListener>("ReadableAllureSelenide")
                UiElementNameRegistry.clear()
            }
        }
    }

    /** Поставка десяти единиц по 100.05 превращается в заявку на три по 120.06; проверяются все поля события и его строки. */
    @Test
    @AllureId("161")
    @DisplayName("Поставка и заявка на три единицы сохраняют точные деньги и все данные сообщения")
    fun shouldPublishCompleteOrderAfterUiDeliveryAndCheckout() {
        val productId = "P-${UUID.randomUUID()}"
        val shortName = "Кабель USB-C «учебный»"
        step("Заполняем поставку десяти единиц товара $productId по 100 рублей 5 копеек") {
            supplierPage.open()
            supplierPage.fill(productId, 10, "100.05", shortName, "Поставка для проверки заявки получателю")
        }
        step("Отправляем поставку товара $productId") { supplierPage.send() }
        step("Проверяем оприходование поставки товара $productId") { supplierPage.checkPosted() }
        val stockItemId = step("Проверяем десять единиц товара $productId в каталоге по 120 рублей 6 копеек") {
            catalogPage.open()
            catalogPage.awaitProduct(productId, 10, "120.06")
            catalogPage.stockItemId(productId)
        }
        val cartId = step("Добавляем три единицы товара $productId в корзину покупателя") {
            catalogPage.add(productId, 3, "120.06")
            catalogPage.cartId(storeId)
        }
        step("Проверяем сумму корзины 360 рублей 18 копеек") { catalogPage.checkTotal("360.18") }
        val submission = step("Оформляем заявку на три единицы товара $productId") {
            val body = interceptResponseBody(getSelenideProxy(), "/stores/$storeId/carts/$cartId/submit") {
                catalogPage.submit()
                catalogPage.checkPublished()
            }
            JsonUtils.objectMapper.readValue<Submission>(body)
        }
        val record = step("Получаем переданную заявку ${submission.submissionId}") {
            val messages = consumer.waitForKeyList(submission.submissionId, max = 1)
            withClue("Получатель должен увидеть заявку ${submission.submissionId} в $topic") { messages.shouldHaveSize(1) }
            messages.single()
        }
        step("Проверяем все данные переданной заявки ${submission.submissionId}") {
            checkEnvelope(record, submission, cartId, "360.18")
            withClue("В заявке должна остаться ровно одна заказанная позиция") { record.value.payload.items.shouldHaveSize(1) }
            checkLine(record.value.payload.items.single(), CartLine(stockItemId, productId, shortName, 3, "120.06", "360.18"))
        }
        step("Проверяем остаток семи единиц товара $productId после оформления") {
            catalogPage.refresh()
            catalogPage.awaitProduct(productId, 7, "120.06")
            catalogPage.checkTotal("360.18")
        }
    }

    /** Две независимые поставки с разными тарифами формируют одну заявку без потерянных или подменённых позиций. */
    @Test
    @AllureId("162")
    @DisplayName("Заявка из двух товаров сохраняет обе позиции, разные тарифные цены и общую сумму")
    fun shouldPublishEveryLineOfUiOrderWithDifferentTariffs() {
        val firstProductId = "P-${UUID.randomUUID()}"
        val secondProductId = "P-${UUID.randomUUID()}"
        val firstName = "Кабель — тариф 20%"
        val secondName = "Адаптер — тариф 25%"
        step("Заполняем поставку десяти единиц товара $firstProductId по 100 рублей") {
            supplierPage.open()
            supplierPage.fill(firstProductId, 10, "100.00", firstName, "Первый товар заявки")
        }
        step("Отправляем первую поставку товара $firstProductId") { supplierPage.send() }
        step("Проверяем оприходование первой поставки") { supplierPage.checkPosted() }
        step("Заполняем новую поставку четырёх единиц товара $secondProductId по 500 рублей") {
            supplierPage.newDelivery()
            supplierPage.fill(secondProductId, 4, "500.00", secondName, "Второй товар заявки")
        }
        step("Отправляем вторую поставку товара $secondProductId") { supplierPage.send() }
        step("Проверяем оприходование второй поставки") { supplierPage.checkPosted() }
        val firstStockId = step("Проверяем десять единиц первого товара в каталоге по 120 рублей") {
            catalogPage.open()
            catalogPage.awaitProduct(firstProductId, 10, "120.00")
            catalogPage.stockItemId(firstProductId)
        }
        val secondStockId = step("Проверяем четыре единицы второго товара в каталоге по 625 рублей") {
            catalogPage.awaitProduct(secondProductId, 4, "625.00")
            catalogPage.stockItemId(secondProductId)
        }
        val cartId = step("Добавляем два первых товара и один второй товар в общую корзину") {
            catalogPage.add(firstProductId, 2, "120.00")
            catalogPage.add(secondProductId, 1, "625.00")
            catalogPage.cartId(storeId)
        }
        step("Проверяем сумму корзины 865 рублей") { catalogPage.checkTotal("865.00") }
        val submission = step("Оформляем заявку с двумя разными товарами") {
            val body = interceptResponseBody(getSelenideProxy(), "/stores/$storeId/carts/$cartId/submit") {
                catalogPage.submit()
                catalogPage.checkPublished()
            }
            JsonUtils.objectMapper.readValue<Submission>(body)
        }
        val record = step("Получаем переданную заявку ${submission.submissionId} с двумя товарами") {
            val messages = consumer.waitForKeyList(submission.submissionId, max = 1)
            withClue("Получатель должен увидеть заявку ${submission.submissionId} в $topic") { messages.shouldHaveSize(1) }
            messages.single()
        }
        step("Проверяем все данные заявки и обе позиции независимо от их порядка") {
            checkEnvelope(record, submission, cartId, "865.00")
            val items = record.value.payload.items
            withClue("Заявка должна содержать обе заказанные позиции") { items.shouldHaveSize(2) }
            withClue("Состав заявки не должен терять, дублировать или подменять товары") {
                items.map { it.productId }.toSet().shouldBe(setOf(firstProductId, secondProductId))
            }
            checkLine(items.single { it.productId == firstProductId }, CartLine(firstStockId, firstProductId, firstName, 2, "120.00", "240.00"))
            checkLine(items.single { it.productId == secondProductId }, CartLine(secondStockId, secondProductId, secondName, 1, "625.00", "625.00"))
        }
        step("Проверяем остатки восьми первых и трёх вторых товаров после оформления") {
            catalogPage.refresh()
            catalogPage.awaitProduct(firstProductId, 8, "120.00")
            catalogPage.awaitProduct(secondProductId, 3, "625.00")
            catalogPage.checkTotal("865.00")
        }
    }

    /** Сопоставляет все поля оболочки и заявки с наблюдавшимся ответом оформления и независимыми ожиданиями сценария. */
    private fun checkEnvelope(record: ConsumedMessage<OrderSubmitted>, submission: Submission, cartId: String, total: String) {
        val event = record.value
        withClue("Топик события заявки") { record.topic.shouldBe(topic) }
        withClue("Kafka key должен маршрутизировать заявку в её магазин") { record.key.shouldBe(storeId) }
        withClue("Магазин ответа оформления") { submission.storeId.shouldBe(storeId) }
        withClue("Корзина ответа оформления") { submission.cartId.shouldBe(cartId) }
        withClue("Идентификатор события должен соответствовать принятой операции") { event.eventId.shouldBe(submission.eventId) }
        withClue("Тип события") { event.eventType.shouldBe("OrderSubmitted") }
        withClue("Версия схемы") { event.schemaVersion.shouldBe(1) }
        withClue("Магазин события") { event.storeId.shouldBe(storeId) }
        withClue("Время события должно соответствовать принятию заявки") { event.occurredAt.shouldBe(submission.acceptedAt) }
        withClue("Идентификатор заявки") { event.payload.submissionId.shouldBe(submission.submissionId) }
        withClue("Корзина заявки") { event.payload.cartId.shouldBe(cartId) }
        withClue("Время принятия заявки") { event.payload.acceptedAt.shouldBe(submission.acceptedAt) }
        withClue("Общая сумма заявки") { event.payload.totalAmount.shouldBe(total) }
        withClue("Валюта заявки") { event.payload.currency.shouldBe("RUB") }
        withClue("Идентификаторы события, заявки и корзины должны быть каноническими UUID") {
            UUID.fromString(event.eventId).toString().shouldBe(event.eventId)
            UUID.fromString(event.payload.submissionId).toString().shouldBe(event.payload.submissionId)
            UUID.fromString(event.payload.cartId).toString().shouldBe(event.payload.cartId)
        }
        withClue("Время заявки должно быть корректным ISO UTC") {
            event.occurredAt.endsWith("Z").shouldBe(true)
            Instant.parse(event.occurredAt).shouldBe(Instant.parse(event.payload.acceptedAt))
        }
        val json = JsonUtils.objectMapper.readTree(record.rawValue)
        withClue("Оболочка сообщения должна содержать ровно поля контракта") {
            json.fieldNames().asSequence().toSet().shouldBe(setOf("eventId", "eventType", "schemaVersion", "occurredAt", "storeId", "payload"))
            json.get("schemaVersion").isIntegralNumber.shouldBe(true)
        }
        withClue("Заявка должна содержать ровно поля контракта") {
            json.get("payload").fieldNames().asSequence().toSet().shouldBe(setOf("submissionId", "cartId", "acceptedAt", "items", "totalAmount", "currency"))
            json.get("payload").get("totalAmount").isTextual.shouldBe(true)
        }
        json.get("payload").get("items").forEach { line ->
            withClue("Позиция заявки должна содержать ровно поля контракта") {
                line.fieldNames().asSequence().toSet().shouldBe(setOf("stockItemId", "productId", "shortName", "quantity", "unitPrice", "lineTotal"))
                line.get("quantity").isIntegralNumber.shouldBe(true)
                line.get("unitPrice").isTextual.shouldBe(true)
                line.get("lineTotal").isTextual.shouldBe(true)
            }
        }
    }

    /** Проверяет каждое поле позиции по исходному товару, количеству покупки и заранее рассчитанной тарифной цене. */
    private fun checkLine(actual: CartLine, expected: CartLine) {
        withClue("Позиция ${expected.productId}: идентификатор остатка из каталога") { actual.stockItemId.shouldBe(expected.stockItemId) }
        withClue("Позиция ${expected.productId}: код товара") { actual.productId.shouldBe(expected.productId) }
        withClue("Позиция ${expected.productId}: исходное название") { actual.shortName.shouldBe(expected.shortName) }
        withClue("Позиция ${expected.productId}: заказанное количество") { actual.quantity.shouldBe(expected.quantity) }
        withClue("Позиция ${expected.productId}: цена с тарифной наценкой") { actual.unitPrice.shouldBe(expected.unitPrice) }
        withClue("Позиция ${expected.productId}: стоимость заказанного количества") { actual.lineTotal.shouldBe(expected.lineTotal) }
    }
}
