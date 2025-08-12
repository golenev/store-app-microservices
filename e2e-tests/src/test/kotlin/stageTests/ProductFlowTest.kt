package stageTests

import config.Database
import constants.Endpoints
import models.ProductPayload
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds

@DisplayName("Проверка потока продукта на уровне e2e")
class ProductFlowTest {

    private var product: ProductPayload? = null

    @AfterEach
    fun cleanup() {
        product?.let {
            val template = Database.template()
            template.update("DELETE FROM cart WHERE barcode_id = ?", it.barcodeId)
            template.update("DELETE FROM product WHERE barcode_id = ?", it.barcodeId)
        }
    }

    @Test
    @DisplayName("товар добавляется один раз и больше не добавляется")
    fun productAppearsInListAndCart() {
        product = ProductPayload(
            barcodeId = 999L,
            shortName = "Test product",
            description = "Desc",
            price = BigDecimal("10.00"),
            quantity = 1,
            addedAtTariffs = LocalDateTime.now().toString(),
            isFoodstuff = false
        )

        val tokenResponse = Given {
            contentType("application/json")
            body(mapOf("username" to "user", "password" to "qwerty"))
        } When {
            post(Endpoints.AUTH)
        }
        tokenResponse.statusCode shouldBeExactly 200
        val token = tokenResponse.jsonPath().getString("token")

        val sendResponse = Given {
            header("Authorization", token)
            contentType("application/json")
            body(product!!)
        } When {
            post(Endpoints.SEND_TO_KAFKA)
        }
        sendResponse.statusCode shouldBeExactly 200

        runBlocking {
            eventually(20.seconds) {
                val listResponse = Given {
                    header("Authorization", token)
                } When {
                    get(Endpoints.PRODUCTS)
                }
                listResponse.statusCode shouldBeExactly 200
                val barcodes = listResponse.jsonPath().getList<Long>("barcodeId")
                barcodes.shouldContain(product!!.barcodeId)
            }
        }

        val addResponse = Given {
            header("Authorization", token)
            contentType("application/json")
            body(mapOf("barcodeId" to product!!.barcodeId))
        } When {
            post(Endpoints.CART)
        }
        addResponse.statusCode shouldBeExactly 200

        val template = Database.template()
        val qty = template.queryForObject(
            "SELECT quantity FROM cart WHERE barcode_id = ?",
            Int::class.java,
            product!!.barcodeId
        )

        qty.shouldNotBeNull()
        qty shouldBeExactly 1

        val repeatResponse = Given {
            header("Authorization", token)
            contentType("application/json")
            body(mapOf("barcodeId" to product!!.barcodeId))
        } When {
            post(Endpoints.CART)
        }
        repeatResponse.statusCode shouldBeExactly 400

        val qtyAfter = template.queryForObject(
            "SELECT quantity FROM cart WHERE barcode_id = ?",
            Int::class.java,
            product!!.barcodeId
        )
        qtyAfter shouldBeExactly 1
    }
}

