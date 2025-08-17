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

    data class AuthRequest(val username: String, val password: String)
    data class AddToCartRequest(val barcodeId: Long)

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
        val token = step("Получаем JWT-токен через POST ${Endpoints.AUTH}") {
            val tokenResponse = HttpClient.post(
                url = Endpoints.AUTH,
                body = AuthRequest("user", "qwerty")
            )
            tokenResponse.statusCode shouldBeExactly 200
            tokenResponse.jsonPath().getString("token")
        }
        logger.info("Получен токен авторизации")

        step("Отправляем товар через POST ${Endpoints.SEND_TO_KAFKA}") {
            val sendResponse = HttpClient.post(
                url = Endpoints.SEND_TO_KAFKA,
                headers = mapOf("Authorization" to token),
                body = product
            )
            sendResponse.statusCode shouldBeExactly 200
        }
        logger.info("Товар отправлен в Kafka через API")

        step("Ожидаем появление товара в списке через GET ${Endpoints.PRODUCTS}") {
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

        step("Добавляем товар в корзину через POST ${Endpoints.CART}") {
            val addResponse = HttpClient.post(
                url = Endpoints.CART,
                headers = mapOf("Authorization" to token),
                body = AddToCartRequest(barcodeId)
            )
            addResponse.statusCode shouldBeExactly 200
        }
        logger.info("Товар добавлен в корзину")

        val qty = step("Считываем количество товара в корзине из БД") {
            Database.queryForObject(
                "SELECT quantity FROM cart WHERE barcode_id = ?",
                Int::class.java,
                barcodeId
            )
        }

        qty.shouldNotBeNull()
        qty shouldBeExactly 1
        logger.info("Проверено количество 1 в корзине для штрихкода {}", barcodeId)

        step("Пытаемся повторно добавить товар и ожидаем код 400") {
            val repeatResponse = HttpClient.post(
                url = Endpoints.CART,
                headers = mapOf("Authorization" to token),
                body = AddToCartRequest(barcodeId)
            )
            repeatResponse.statusCode shouldBeExactly 400
        }
        logger.info("Повторное добавление в корзину вернуло 400, как и ожидалось")

        val qtyAfter = step("Повторно читаем количество товара в корзине") {
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

