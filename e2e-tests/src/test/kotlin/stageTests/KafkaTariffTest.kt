package stageTests

import config.Database
import config.Database.template
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import models.ProductPayload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import positiveConfig
import testUtil.KafkaProducerImpl
import java.math.BigDecimal
import java.time.LocalDateTime

@DisplayName("Проверка получения товара через Kafka")
class KafkaTariffTest {

    private var barcodeId: Long = (10000000000..99999999999).random()

    @AfterEach
    fun cleanup() {
        barcodeId.let {
            val template = Database.template()
            template.update("DELETE FROM cart WHERE barcode_id = ?", it)
            template.update("DELETE FROM product WHERE barcode_id = ?", it)
        }
    }

    private fun sendAndAssert(payload: ProductPayload, markupCoefficient: BigDecimal) {
        payload.barcodeId = barcodeId
        KafkaProducerImpl().sendMessage("send-topic", payload)

        val expectedPrice = payload.price.add(
            payload.price.multiply(markupCoefficient).divide(BigDecimal.valueOf(100))
        )

        runBlocking {
            eventually(positiveConfig) {
                val price =
                    template().queryForObject(
                        "SELECT price FROM product WHERE barcode_id = ?",
                        BigDecimal::class.java,
                        barcodeId
                    )

                price.compareTo(expectedPrice) shouldBe 0
            }
        }
    }

    @Test
    @DisplayName("наценка применяется для food_100")
    fun testFood100() {
        sendAndAssert(
            ProductPayload(
                shortName = "food999",
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

