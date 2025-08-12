package stageTests

import config.Database
import models.ProductPayload
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import testUtil.KafkaProducerImpl
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@DisplayName("Проверка получения товара через Kafka")
class KafkaTests {

    private var barcodeId: Long? = null

    @AfterEach
    fun cleanup() {
        barcodeId?.let {
            val template = Database.template()
            template.update("DELETE FROM cart WHERE barcode_id = ?", it)
            template.update("DELETE FROM product WHERE barcode_id = ?", it)
        }
    }

    private fun sendAndAssert(payload: ProductPayload, markupCoefficient: BigDecimal) {
        barcodeId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
        payload.barcodeId = barcodeId
        val producer = KafkaProducerImpl()
        producer.sendMessage("send-topic", payload)

        val template = Database.template()
        val expectedPrice = payload.price.add(
            payload.price.multiply(markupCoefficient).divide(BigDecimal.valueOf(100))
        )

        runBlocking {
            eventually(30.seconds) {
                val price = try {
                    template.queryForObject(
                        "SELECT price FROM product WHERE barcode_id = ?",
                        BigDecimal::class.java,
                        barcodeId
                    )
                } catch (e: EmptyResultDataAccessException) {
                    throw AssertionError("Запись ещё не создана", e)
                }
                price.compareTo(expectedPrice) shouldBe 0
            }
        }
    }

    @Test
    @DisplayName("наценка применяется для food_100")
    fun testFood100() {
        sendAndAssert(
            ProductPayload(
                shortName = "food100",
                description = "desc",
                price = BigDecimal("100"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = true
            ),
            BigDecimal.valueOf(1)
        )
    }

    @Test
    @DisplayName("наценка применяется для food_300")
    fun testFood300() {
        sendAndAssert(
            ProductPayload(
                shortName = "food300",
                description = "desc",
                price = BigDecimal("200"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = true
            ),
            BigDecimal.valueOf(3)
        )
    }

    @Test
    @DisplayName("наценка применяется для food_500")
    fun testFood500() {
        sendAndAssert(
            ProductPayload(
                shortName = "food500",
                description = "desc",
                price = BigDecimal("400"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = true
            ),
            BigDecimal.valueOf(5)
        )
    }

    @Test
    @DisplayName("наценка применяется для food_1000")
    fun testFood1000() {
        sendAndAssert(
            ProductPayload(
                shortName = "food1000",
                description = "desc",
                price = BigDecimal("600"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = true
            ),
            BigDecimal.valueOf(10)
        )
    }

    @Test
    @DisplayName("наценка применяется для not_food_100")
    fun testNotFood100() {
        sendAndAssert(
            ProductPayload(
                shortName = "notfood100",
                description = "desc",
                price = BigDecimal("100"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = false
            ),
            BigDecimal.valueOf(5)
        )
    }

    @Test
    @DisplayName("наценка применяется для not_food_500")
    fun testNotFood500() {
        sendAndAssert(
            ProductPayload(
                shortName = "notfood500",
                description = "desc",
                price = BigDecimal("400"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = false
            ),
            BigDecimal.valueOf(10)
        )
    }

    @Test
    @DisplayName("наценка применяется для not_food_1000")
    fun testNotFood1000() {
        sendAndAssert(
            ProductPayload(
                shortName = "notfood1000",
                description = "desc",
                price = BigDecimal("600"),
                quantity = 1,
                addedAtTariffs = LocalDateTime.now().toString(),
                isFoodstuff = false
            ),
            BigDecimal.valueOf(20)
        )
    }
}

