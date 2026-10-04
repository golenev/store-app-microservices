package org.golenev.ui.config

import org.golenev.utils.awaitState
import org.golenev.utils.required
import com.codeborne.selenide.logevents.SelenideLogger
import org.golenev.utils.*
import org.golenev.db.tables.observation.ObservationDao
import io.qameta.allure.Allure
import java.util.UUID

/** Ресурсы браузерного сценария: локальный прокси, окна и новые товары с UUID в изолированных магазинах S-1 и S-2. */
class BrowserScenario(private val resources: ScenarioResources) {
    val scope: Scope = "S-1" to "MOSCOW"
    private val products = mutableSetOf<String>()
    private val initialCarts = ObservationDao.strings("store", "SELECT cart_id::text FROM carts WHERE store_id IN ('S-1','S-2')").toSet()
    val proxy = BrowserProxy()

    init {
        resources.onClose("Удаляем только данные, созданные этим UI-сценарием") { purgeDelta() }
        resources.onClose("Закрываем локальный браузерный прокси") { proxy.close() }
        resources.onClose("Сохраняем историю браузерных запросов") { proxy.attachEvidence() }
    }

    /** Регистрирует productId до отправки формы. Очистка учитывает потерянный ответ и частично успешную подготовку. */
    fun product(): String {
        val product = "P-${UUID.randomUUID()}"
        products += product
        return product
    }

    /** Создаёт независимый Chrome, делает его текущим для Selenide и регистрирует диагностику/закрытие до действий сценария. Возвращает обычный WebDriver. */
    fun browser(mobile: Boolean = false): org.openqa.selenium.WebDriver {
        val config = DriverConfig()
        config.setup(proxy.baseUrl, mobile)
        val driver = org.openqa.selenium.chrome.ChromeDriver(config.getChromeOptions(mobile))
        resources.onClose("Закрываем свой браузер") {
            com.codeborne.selenide.WebDriverRunner.setWebDriver(driver)
            com.codeborne.selenide.Selenide.closeWebDriver()
        }
        resources.onClose("Сохраняем DOM и скриншот своего браузера") {
            Allure.addAttachment("DOM браузера", "text/html", driver.pageSource)
            val screenshot = driver.getScreenshotAs(org.openqa.selenium.OutputType.BYTES)
            Allure.addAttachment("Скриншот браузера", "image/png", screenshot.inputStream(), ".png")
        }
        com.codeborne.selenide.WebDriverRunner.setWebDriver(driver)
        return driver
    }

    /** Ждёт приход продукта в STORE по UUID и явно ожидаемому количеству. Неожиданные ошибки чтения передаются вызывающему коду. */
    fun stock(product: String, quantity: Int): org.golenev.commondto.Stock {
        val stock = awaitState("UI product=$product expected quantity=$quantity", read = { Shop.stock(scope, product) },
            ready = { it?.availableQuantity == quantity })
        return required(stock, "UI inventory product=$product")
    }

    /** Проверяет количество и сумму логических списаний своего продукта. Физические сетевые повторы учитываются отдельно. */
    fun checkExpense(product: String, expectedCount: Int, expectedQuantity: Int) {
        org.junit.jupiter.api.Assertions.assertEquals(expectedCount.toString(), ObservationDao.scalar("store",
            "SELECT count(*) FROM stock_expenses e JOIN inventory i USING(stock_item_id) WHERE i.product_id=?", product))
        org.junit.jupiter.api.Assertions.assertEquals(expectedQuantity.toString(), ObservationDao.scalar("store",
            "SELECT COALESCE(sum(e.quantity),0) FROM stock_expenses e JOIN inventory i USING(stock_item_id) WHERE i.product_id=?", product))
    }

    /** Удаляет только свои товары, поставки и корзины, созданные после исходного снимка. Монотонный порядок приёмки не откатывается. */
    private fun purgeDelta() {
        awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
        awaitConsumerDrain("warehouse.goods-posted", "store-goods-v1")
        val carts = ObservationDao.strings("store", "SELECT cart_id::text FROM carts WHERE store_id IN ('S-1','S-2')").toSet() - initialCarts
        for (cart in carts) {
            ObservationDao.update("store", "DELETE FROM store_outbox WHERE submission_id IN (SELECT submission_id FROM submissions WHERE cart_id=?::uuid)", cart)
            ObservationDao.update("store", "DELETE FROM stock_expenses WHERE submission_id IN (SELECT submission_id FROM submissions WHERE cart_id=?::uuid)", cart)
            for (table in listOf("submissions", "cart_items", "carts")) ObservationDao.update("store", "DELETE FROM $table WHERE cart_id=?::uuid", cart)
        }
        for (product in products) {
            val deliveries = ObservationDao.strings("warehouse", "SELECT delivery_id FROM delivery_items WHERE product_id=?", product)
            ObservationDao.update("store", "DELETE FROM stock_movements WHERE stock_item_id IN (SELECT stock_item_id FROM inventory WHERE product_id=?)", product)
            ObservationDao.update("store", "DELETE FROM inventory WHERE product_id=?", product)
            for (delivery in deliveries) {
                for (table in listOf("processed_events", "stock_receipts")) ObservationDao.update("store", "DELETE FROM $table WHERE delivery_id=?", delivery)
                for (table in listOf("warehouse_outbox", "received_events", "delivery_items", "deliveries")) ObservationDao.update("warehouse", "DELETE FROM $table WHERE delivery_id=?", delivery)
            }
        }
    }
}
