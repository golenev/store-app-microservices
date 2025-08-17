package stageTests

import config.Database
import config.HttpClient
import constants.Endpoints
import helpers.halfUpRound
import helpers.step
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

@DisplayName("Проверка оформления заказа")
class OrderE2ETest {
    private val logger = LoggerFactory.getLogger(OrderE2ETest::class.java)
    private val barcodeFood = (10000000000..19999999999).random().toLong()
    private val barcodeNonFood = (20000000000..29999999999).random().toLong()
    private val orderId = (30000000000..39999999999).random().toLong()

    data class AuthRequest(val username: String, val password: String)
    data class AddToCartRequest(val barcodeId: Long)
    data class CartItem(
        val barcodeId: Long,
        val shortName: String,
        val description: String,
        val price: BigDecimal,
        val quantity: Int,
        val total: BigDecimal
    )
    data class OrderRequest(
        val id: Long,
        val createdAt: String,
        val orderSum: BigDecimal,
        val items: List<CartItem>
    )

    @AfterEach
    fun cleanup() {
        Database.update("DELETE FROM orders WHERE id = ?", orderId)
        Database.update("DELETE FROM cart WHERE barcode_id IN (?, ?)", barcodeFood, barcodeNonFood)
        Database.update("DELETE FROM product WHERE barcode_id IN (?, ?)", barcodeFood, barcodeNonFood)
    }

    @Test
    fun orderCreatedAndPersisted() {
        step("Добавляем тестовые продукты напрямую в БД") {
            val now = LocalDateTime.now()
            Database.update(
                "INSERT INTO product (barcode_id, short_name, description, price, quantity, added_at_tariffs, is_foodstuff) VALUES (?, ?, ?, ?, ?, ?, ?)",
                barcodeFood, "Молоко", "Пищевой товар", BigDecimal("10.00"), 5, now, true
            )
            Database.update(
                "INSERT INTO product (barcode_id, short_name, description, price, quantity, added_at_tariffs, is_foodstuff) VALUES (?, ?, ?, ?, ?, ?, ?)",
                barcodeNonFood, "Мыло", "Не пищевой товар", BigDecimal("5.00"), 3, now, false
            )
        }
        logger.info("Продукты добавлены в БД: {} и {}", barcodeFood, barcodeNonFood)

        val token = step("Получаем JWT-токен через POST ${Endpoints.AUTH}") {
            val auth = HttpClient.post(
                url = Endpoints.AUTH,
                baseUri = Endpoints.BASE_URL,
                body = AuthRequest("user", "qwerty")
            )
            auth.statusCode shouldBeExactly 200
            auth.jsonPath().getString("token")
        }

        step("Запрашиваем /api/products и проверяем наличие добавленных товаров") {
            val resp = HttpClient.get(
                url = Endpoints.PRODUCTS,
                headers = mapOf("Authorization" to token),
                baseUri = Endpoints.BASE_URL
            )
            resp.statusCode shouldBeExactly 200
            val barcodes = resp.jsonPath().getList<Long>("barcodeId")
            barcodes.shouldContain(barcodeFood)
            barcodes.shouldContain(barcodeNonFood)
        }

        step("Добавляем товары в корзину через POST ${Endpoints.CART}") {
            listOf(barcodeFood, barcodeNonFood).forEach { code ->
                val addResp = HttpClient.post(
                    url = Endpoints.CART,
                    headers = mapOf("Authorization" to token),
                    baseUri = Endpoints.BASE_URL,
                    body = AddToCartRequest(code)
                )
                addResp.statusCode shouldBeExactly 200
            }
        }

        val cartItems = step("Получаем содержимое корзины и проверяем детали каждого товара") {
            val cartResp = HttpClient.get(
                url = Endpoints.CART,
                headers = mapOf("Authorization" to token),
                baseUri = Endpoints.BASE_URL
            )
            cartResp.statusCode shouldBeExactly 200
            val items = cartResp.jsonPath().getList("", CartItem::class.java)
            items.shouldHaveSize(2)
            items.find { it.barcodeId == barcodeFood }!!.apply {
                shortName shouldBe "Молоко"
                description shouldBe "Пищевой товар"
                quantity shouldBeExactly 1
            }
            items.find { it.barcodeId == barcodeNonFood }!!.apply {
                shortName shouldBe "Мыло"
                description shouldBe "Не пищевой товар"
                quantity shouldBeExactly 1
            }
            logger.info("Корзина содержит товары: {}", items)
            items
        }

        val orderSum = cartItems.fold(BigDecimal.ZERO) { acc, item ->
            acc + item.total
        }

        step("Оформляем заказ через POST ${Endpoints.ORDER}") {
            val order = OrderRequest(
                id = orderId,
                createdAt = LocalDateTime.now().toString(),
                orderSum = orderSum,
                items = cartItems
            )
            val orderResp = HttpClient.post(
                url = Endpoints.ORDER,
                baseUri = Endpoints.BASE_URL,
                headers = mapOf("Authorization" to token),
                body = order
            )
            orderResp.statusCode shouldBeExactly 200
        }

        step("Проверяем сохранение заказа и состава корзины в БД") {
            val sum = Database.queryForObject(
                "SELECT order_sum FROM orders WHERE id = ?",
                BigDecimal::class.java,
                orderId
            )
            sum.shouldNotBeNull()
            sum.halfUpRound() shouldBe orderSum.halfUpRound()
            val itemsJson = Database.queryForObject(
                "SELECT items::text FROM orders WHERE id = ?",
                String::class.java,
                orderId
            )
            val itemsInDb: List<CartItem> = jacksonObjectMapper().readValue(itemsJson)
            itemsInDb.shouldHaveSize(2)
            itemsInDb.find { it.barcodeId == barcodeFood }!!.apply {
                shortName shouldBe "Молоко"
                quantity shouldBeExactly 1
            }
            itemsInDb.find { it.barcodeId == barcodeNonFood }!!.apply {
                shortName shouldBe "Мыло"
                quantity shouldBeExactly 1
            }
        }
    }
}
