package stageTests

import config.Database
import config.HttpClient
import constants.Endpoints
import models.ProductPayload
import helpers.step
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import positiveConfig

@DisplayName("Проверка потока продукта на уровне e2e")
class ProductFlowE2ETest {
    private val logger = LoggerFactory.getLogger(ProductFlowE2ETest::class.java)
    private var barcodeId: Long = (10000000000..99999999999).random()

    @AfterEach
    fun cleanup() {
        Database.update("DELETE FROM cart WHERE barcode_id = ?", barcodeId)
        Database.update("DELETE FROM product WHERE barcode_id = ?", barcodeId)
    }

    @Test
    @DisplayName("товар добавляется один раз и больше не добавляется")
    fun productAppearsInListAndCart() {
        val product = ProductPayload(
            barcodeId = barcodeId,
            shortName = "Test product",
            description = "Desc",
            price = BigDecimal("10.00"),
            quantity = 1,
            addedAtTariffs = LocalDateTime.now().toString(),
            isFoodstuff = false
        )
        logger.info("Сгенерирован товар со штрихкодом {}", barcodeId)
        val token = step("Получение токена") {
            val tokenResponse = HttpClient.post(
                url = Endpoints.AUTH,
                body = mapOf("username" to "user", "password" to "qwerty")
            )
            tokenResponse.statusCode shouldBeExactly 200
            tokenResponse.jsonPath().getString("token")
        }
        logger.info("Получен токен авторизации")

        step("Отправка товара через API") {
            val sendResponse = HttpClient.post(
                url = Endpoints.SEND_TO_KAFKA,
                headers = mapOf("Authorization" to token),
                body = product
            )
            sendResponse.statusCode shouldBeExactly 200
        }
        logger.info("Товар отправлен в Kafka через API")

        step("Проверка появления товара в списке") {
            runBlocking {
                eventually(positiveConfig) {
                    val listResponse = HttpClient.get(
                        url = Endpoints.PRODUCTS,
                        headers = mapOf("Authorization" to token)
                    )
                    listResponse.statusCode shouldBeExactly 200
                    val barcodes = listResponse.jsonPath().getList<Long>("barcodeId")
                    barcodes.shouldContain(barcodeId)
                    logger.info("Товар со штрихкодом {} появился в списке товаров", barcodeId)
                }
            }
        }

        step("Добавление товара в корзину") {
            val addResponse = HttpClient.post(
                url = Endpoints.CART,
                headers = mapOf("Authorization" to token),
                body = mapOf("barcodeId" to barcodeId)
            )
            addResponse.statusCode shouldBeExactly 200
        }
        logger.info("Товар добавлен в корзину")

        val qty = step("Проверка количества в корзине") {
            Database.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Int::class.java,
                barcodeId
            )
        }

        qty.shouldNotBeNull()
        qty shouldBeExactly 1
        logger.info("Проверено количество 1 в корзине для штрихкода {}", barcodeId)

        step("Проверка невозможности повторного добавления") {
            val repeatResponse = HttpClient.post(
                url = Endpoints.CART,
                headers = mapOf("Authorization" to token),
                body = mapOf("barcodeId" to barcodeId)
            )
            repeatResponse.statusCode shouldBeExactly 400
        }
        logger.info("Повторное добавление в корзину вернуло 400, как и ожидалось")

        val qtyAfter = step("Итоговое количество в корзине") {
            Database.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Int::class.java,
                barcodeId
            )
        }
        qtyAfter shouldBeExactly 1
        logger.info("Итоговое количество остаётся 1 для штрихкода {}", barcodeId)
    }
}

