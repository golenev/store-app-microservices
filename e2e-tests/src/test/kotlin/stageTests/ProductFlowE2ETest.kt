package stageTests

import config.Database
import config.HttpClient
import constants.Endpoints
import models.ProductPayload
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
        val template = Database.template()
        template.update("DELETE FROM cart WHERE barcode_id = ?", barcodeId)
        template.update("DELETE FROM product WHERE barcode_id = ?", barcodeId)
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
        logger.info("Generated product with barcode {}", barcodeId)
        val tokenResponse = HttpClient.post(
            url = Endpoints.AUTH,
            body = mapOf("username" to "user", "password" to "qwerty")
        )
        tokenResponse.statusCode shouldBeExactly 200
        val token = tokenResponse.jsonPath().getString("token")
        logger.info("Received auth token")

        val sendResponse = HttpClient.post(
            url = Endpoints.SEND_TO_KAFKA,
            headers = mapOf("Authorization" to token),
            body = product
        )
        sendResponse.statusCode shouldBeExactly 200
        logger.info("Product sent to Kafka via API")

        runBlocking {
            eventually(positiveConfig) {
                val listResponse = HttpClient.get(
                    url = Endpoints.PRODUCTS,
                    headers = mapOf("Authorization" to token)
                )
                listResponse.statusCode shouldBeExactly 200
                val barcodes = listResponse.jsonPath().getList<Long>("barcodeId")
                barcodes.shouldContain(barcodeId)
                logger.info("Product with barcode {} appeared in product list", barcodeId)
            }
        }

        val addResponse = HttpClient.post(
            url = Endpoints.CART,
            headers = mapOf("Authorization" to token),
            body = mapOf("barcodeId" to barcodeId)
        )
        addResponse.statusCode shouldBeExactly 200
        logger.info("Product added to cart")

        val template = Database.template()
        val qty = template.queryForObject(
            "SELECT quantity FROM cart WHERE barcode_id = ?",
            Int::class.java,
            barcodeId
        )

        qty.shouldNotBeNull()
        qty shouldBeExactly 1
        logger.info("Verified quantity 1 in cart for barcode {}", barcodeId)

        val repeatResponse = HttpClient.post(
            url = Endpoints.CART,
            headers = mapOf("Authorization" to token),
            body = mapOf("barcodeId" to barcodeId)
        )
        repeatResponse.statusCode shouldBeExactly 400
        logger.info("Duplicate add to cart returned 400 as expected")

        val qtyAfter = template.queryForObject(
            "SELECT quantity FROM cart WHERE barcode_id = ?",
            Int::class.java,
            barcodeId
        )
        qtyAfter shouldBeExactly 1
        logger.info("Final quantity remains 1 for barcode {}", barcodeId)
    }
}

