package stageTests

import config.Database
import config.HttpClient
import constants.Endpoints
import helpers.step
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.qameta.allure.AllureId
import kotlinx.coroutines.runBlocking
import models.ProductPayload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import positiveConfig
import testUtil.KafkaProducerImpl
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.system.measureTimeMillis

@DisplayName("Проверка получения товара через Kafka")
class KafkaTariffTest {
    private val logger = LoggerFactory.getLogger(KafkaTariffTest::class.java)
    private var barcodeId: Long = (10000000000..99999999999).random()

    @AfterEach
    fun cleanup() {
        barcodeId.let {
            logger.info("Удаляем записи для штрихкода {}", it)
            Database.update("DELETE FROM cart WHERE barcode_id = ?", it)
            Database.update("DELETE FROM product WHERE barcode_id = ?", it)
        }
    }

    private fun sendAndAssert(payload: ProductPayload, markupCoefficient: BigDecimal) {
        step("Отправляем сообщение о товаре в Kafka") {
            payload.barcodeId = barcodeId
            logger.info("Отправляем сообщение со штрихкодом {} в Kafka", barcodeId)
            KafkaProducerImpl().sendMessage("send-topic", payload)
        }

        val expectedPrice = payload.price.add(
            payload.price.multiply(markupCoefficient).divide(BigDecimal.valueOf(100))
        )

        step("Ждём появления товара в БД и проверяем цену с учётом наценки") {
            runBlocking {
                eventually(positiveConfig) {
                    val price = Database.queryForObject(
                        "SELECT price FROM product WHERE barcode_id = ?",
                        BigDecimal::class.java,
                        barcodeId
                    )

                    price.compareTo(expectedPrice) shouldBe 0
                    logger.info("Проверена цена {} для штрихкода {}", price, barcodeId)
                }
            }
        }
    }

    @AllureId("15")
    @Test
    @DisplayName("наценка применяется для food_100")
    fun testFood100() {
        step("Отправляем товар 'food_100' и проверяем наценку 1%") {
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
    }

    @AllureId("16")
    @Test
    @DisplayName("наценка применяется для food_300")
    fun testFood300() {
        step("Отправляем товар 'food_300' и проверяем наценку 3%") {
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
    }

    @AllureId("17")
    @Test
    @DisplayName("наценка применяется для food_500")
    fun testFood500() {
        step("Отправляем товар 'food_500' и проверяем наценку 5%") {
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
    }

    @AllureId("18")
    @Test
    @DisplayName("наценка применяется для food_1000")
    fun testFood1000() {
        step("Отправляем товар 'food_1000' и проверяем наценку 10%") {
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
    }

    @AllureId("19")
    @Test
    @DisplayName("наценка применяется для not_food_100")
    fun testNotFood100() {
        step("Отправляем товар 'not_food_100' и проверяем наценку 5%") {
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
    }

    @AllureId("20")
    @Test
    @DisplayName("наценка применяется для not_food_500")
    fun testNotFood500() {
        step("Отправляем товар 'not_food_500' и проверяем наценку 10%") {
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
    }

    @AllureId("20")
    @Test
    @DisplayName("наценка применяется для not_food_1000")
    fun testNotFood1000() {
        step("Отправляем товар 'not_food_1000' и проверяем наценку 20%") {
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

    @AllureId("21")
    @Test
    @DisplayName("первый запрос тарифов медленный, повторный быстрый")
    fun tariffsCacheTimeTest() {
        step("Сбрасываем кэш тарифов через POST /api/v1/resetCache?now=true") {
            val response = HttpClient.post(
                url = "/api/v1/resetCache?now=true",
                baseUri = Endpoints.TARIFFS_BASE_URL
            )
            response.statusCode shouldBe 200
        }

        step("Первый GET ${Endpoints.TARIFFS}?all=true должен выполняться более 5 секунд") {
            val duration = measureTimeMillis {
                val resp = HttpClient.get(
                    url = Endpoints.TARIFFS,
                    params = mapOf("all" to true),
                    baseUri = Endpoints.TARIFFS_BASE_URL
                )
                resp.statusCode shouldBe 200
            }
            duration.shouldBeGreaterThan(5000)
            logger.info("Первый запрос занял {} мс", duration)
        }

        step("Повторный GET ${Endpoints.TARIFFS}?all=true должен быть быстрее 5 секунд") {
            val duration = measureTimeMillis {
                val resp = HttpClient.get(
                    url = Endpoints.TARIFFS,
                    params = mapOf("all" to true),
                    baseUri = Endpoints.TARIFFS_BASE_URL
                )
                resp.statusCode shouldBe 200
            }
            duration.shouldBeLessThan(5000)
            logger.info("Повторный запрос занял {} мс", duration)
        }
    }
}

