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
import java.math.BigDecimal
import java.time.LocalDateTime
import positiveConfig

@DisplayName("Проверка потока продукта на уровне e2e")
class ProductFlowE2ETest {

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

        val tokenResponse = HttpClient.post(
            url = Endpoints.AUTH,
            body = mapOf("username" to "user", "password" to "qwerty")
        )
        tokenResponse.statusCode shouldBeExactly 200
        val token = tokenResponse.jsonPath().getString("token")

        val sendResponse = HttpClient.post(
            url = Endpoints.SEND_TO_KAFKA,
            headers = mapOf("Authorization" to token),
            body = product
        )
        sendResponse.statusCode shouldBeExactly 200

        runBlocking {
            eventually(positiveConfig) {
                val listResponse = HttpClient.get(
                    url = Endpoints.PRODUCTS,
                    headers = mapOf("Authorization" to token)
                )
                listResponse.statusCode shouldBeExactly 200
                val barcodes = listResponse.jsonPath().getList<Long>("barcodeId")
                barcodes.shouldContain(barcodeId)
            }
        }

        val addResponse = HttpClient.post(
            url = Endpoints.CART,
            headers = mapOf("Authorization" to token),
            body = mapOf("barcodeId" to barcodeId)
        )
        addResponse.statusCode shouldBeExactly 200

        val template = Database.template()
        val qty = template.queryForObject(
            "SELECT quantity FROM cart WHERE barcode_id = ?",
            Int::class.java,
            barcodeId
        )

        qty.shouldNotBeNull()
        qty shouldBeExactly 1

        val repeatResponse = HttpClient.post(
            url = Endpoints.CART,
            headers = mapOf("Authorization" to token),
            body = mapOf("barcodeId" to barcodeId)
        )
        repeatResponse.statusCode shouldBeExactly 400

        val qtyAfter = template.queryForObject(
            "SELECT quantity FROM cart WHERE barcode_id = ?",
            Int::class.java,
            barcodeId
        )
        qtyAfter shouldBeExactly 1
    }
}

