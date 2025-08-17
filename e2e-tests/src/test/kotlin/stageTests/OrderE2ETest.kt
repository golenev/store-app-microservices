package stageTests

import config.Database
import config.HttpClient
import constants.Endpoints
import helpers.step
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime

@DisplayName("Проверка оформления заказа")
class OrderE2ETest {
    private val logger = LoggerFactory.getLogger(OrderE2ETest::class.java)
    private val barcodeFood = (10000000000..19999999999).random().toLong()
    private val barcodeNonFood = (20000000000..29999999999).random().toLong()
    private val orderId = (30000000000..39999999999).random().toLong()

    @AfterEach
    fun cleanup() {
        Database.update("DELETE FROM orders WHERE id = ?", orderId)
        Database.update("DELETE FROM cart WHERE barcode_id IN (?, ?)", barcodeFood, barcodeNonFood)
        Database.update("DELETE FROM product WHERE barcode_id IN (?, ?)", barcodeFood, barcodeNonFood)
    }

    @Test
    fun orderCreatedAndPersisted() {
        step("Добавление продуктов в базу") {
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

        val token = step("Получение токена") {
            val auth = HttpClient.post(
                url = Endpoints.AUTH,
                baseUri = Endpoints.BASE_URL,
                body = mapOf("username" to "user", "password" to "qwerty")
            )
            auth.statusCode shouldBeExactly 200
            auth.jsonPath().getString("token")
        }

        step("Проверка наличия товаров через HTTP") {
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

        step("Добавление товаров в корзину") {
            listOf(barcodeFood, barcodeNonFood).forEach { code ->
                val addResp = HttpClient.post(
                    url = Endpoints.CART,
                    headers = mapOf("Authorization" to token),
                    baseUri = Endpoints.BASE_URL,
                    body = mapOf("barcodeId" to code)
                )
                addResp.statusCode shouldBeExactly 200
            }
        }

        val cartItems = step("Получение корзины") {
            val cartResp = HttpClient.get(
                url = Endpoints.CART,
                headers = mapOf("Authorization" to token),
                baseUri = Endpoints.BASE_URL
            )
            cartResp.statusCode shouldBeExactly 200
            cartResp.jsonPath().getList<Map<String, Any>>("")
        }

        cartItems.size shouldBe 2
        logger.info("В корзине {} товаров", cartItems.size)

        val orderSum = cartItems.fold(BigDecimal.ZERO) { acc, item ->
            val total = item["total"].toString()
            acc + BigDecimal(total)
        }

        step("Оформление заказа") {
            val order = mapOf(
                "id" to orderId,
                "createdAt" to LocalDateTime.now().toString(),
                "orderSum" to orderSum,
                "items" to cartItems
            )
            val orderResp = HttpClient.post(
                url = Endpoints.ORDER,
                baseUri = Endpoints.BASE_URL,
                body = order
            )
            orderResp.statusCode shouldBeExactly 200
        }

        step("Проверка сохранения заказа в БД") {
            val sum = Database.queryForObject(
                "SELECT order_sum FROM orders WHERE id = ?",
                BigDecimal::class.java,
                orderId
            )
            sum.shouldNotBeNull()
            sum shouldBe orderSum
            val itemsJson = Database.queryForObject(
                "SELECT items::text FROM orders WHERE id = ?",
                String::class.java,
                orderId
            )
            itemsJson.shouldContainString(barcodeFood.toString())
            itemsJson.shouldContainString(barcodeNonFood.toString())
        }
    }
}
