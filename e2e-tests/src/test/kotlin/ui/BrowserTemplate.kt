package ui

import awaitState
import required
import com.codeborne.selenide.SelenideConfig
import com.codeborne.selenide.SelenideDriver
import com.codeborne.selenide.logevents.SelenideLogger
import helpers.*
import config.Database
import io.qameta.allure.Allure
import io.qameta.allure.selenide.AllureSelenide
import org.openqa.selenium.chrome.ChromeOptions
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** One owned browser window/profile; page objects retain their driver and no static Selenide state is shared. */
class BrowserWindow internal constructor(private val driver: SelenideDriver, baseUrl: String) : AutoCloseable {
    val catalog = CatalogPage(driver, baseUrl)
    val supplier = SupplierPage(driver, baseUrl)
    val navigation = NavigationPage(driver, baseUrl)

    /** Captures the final DOM/screenshot before closing this window; evidence errors cannot prevent driver cleanup. */
    fun evidence() {
        if (!driver.hasWebDriverStarted()) return
        Allure.addAttachment("Browser DOM", "text/html", driver.source())
        val screenshot = driver.screenshot("final-${UUID.randomUUID()}")
        val path = screenshot?.let { if (it.startsWith("file:")) java.nio.file.Path.of(java.net.URI.create(it)) else File(it).toPath() }
        if (path != null) Files.newInputStream(path).use {
            Allure.addAttachment("Browser screenshot", "image/png", it, ".png")
        }
    }

    /** Closes the owned WebDriver/profile; no user browser or application service is controlled. */
    override fun close() {
        driver.close()
    }
}

/** Browser scenario owns its relay, windows and UUID product delta in the isolated S-1/S-2 HTML fixtures. */
class BrowserScenario(private val resources: ScenarioResources) {
    val scope: Scope = "S-1" to "MOSCOW"
    private val products = mutableSetOf<String>()
    private val initialCarts = Database.strings("store", "SELECT cart_id::text FROM carts WHERE store_id IN ('S-1','S-2')").toSet()
    val proxy = BrowserProxy()

    init {
        resources.onClose("Remove only this UI scenario's fixture delta") { purgeDelta() }
        resources.onClose("Close browser relay") { proxy.close() }
        resources.onClose("Attach browser network evidence") { proxy.attachEvidence() }
    }

    /** Registers the product before form submission so cleanup also covers a lost or partially successful setup reply. */
    fun product(): String {
        val product = "P-${UUID.randomUUID()}"
        products += product
        return product
    }

    /** Creates a separate Chrome profile with instance configuration; mobile emulation fixes the actual CSS viewport at 390 pixels. */
    fun window(mobile: Boolean = false): BrowserWindow {
        val options = ChromeOptions().addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-notifications")
        System.getenv("E2E_CHROME_BINARY")?.let { options.setBinary(it) }
        System.getenv("E2E_CHROME_DRIVER")?.let { System.setProperty("webdriver.chrome.driver", it) }
        if (mobile) options.setExperimentalOption("mobileEmulation", mapOf("deviceMetrics" to mapOf("width" to 390, "height" to 844, "pixelRatio" to 1)))
        val reports = File(System.getenv("E2E_ALLURE_RESULTS") ?: "build/allure-results").parentFile.resolve("browser-evidence")
        val driver = SelenideDriver(SelenideConfig().browser("chrome").headless(true).browserSize("1280x900")
            .timeout(30000).reportsFolder(reports.absolutePath).browserCapabilities(options))
        val window = BrowserWindow(driver, proxy.baseUrl)
        resources.onClose("Close owned browser window") { window.close() }
        resources.onClose("Capture browser window evidence") { window.evidence() }
        return window
    }

    /** Awaits STORE consumption by UUID product and an independent quantity expectation; unexpected read errors propagate. */
    fun stock(product: String, quantity: Int): models.Stock {
        val stock = awaitState("UI product=$product expected quantity=$quantity", read = { Shop.stock(scope, product) },
            ready = { it?.availableQuantity == quantity })
        return required(stock, "UI inventory product=$product")
    }

    /** Confirms one logical expense for the scenario's UUID product; physical network retries are counted separately. */
    fun checkExpense(product: String, expectedCount: Int, expectedQuantity: Int) {
        org.junit.jupiter.api.Assertions.assertEquals(expectedCount.toString(), Database.scalar("store",
            "SELECT count(*) FROM stock_expenses e JOIN inventory i USING(stock_item_id) WHERE i.product_id=?", product))
        org.junit.jupiter.api.Assertions.assertEquals(expectedQuantity.toString(), Database.scalar("store",
            "SELECT COALESCE(sum(e.quantity),0) FROM stock_expenses e JOIN inventory i USING(stock_item_id) WHERE i.product_id=?", product))
    }

    /** Deletes only owned products/deliveries and carts created after the snapshot; immutable fixture sequence high-water marks remain valid. */
    private fun purgeDelta() {
        awaitConsumerDrain("logistics.deliveries", "warehouse-deliveries-v1")
        awaitConsumerDrain("warehouse.goods-posted", "store-goods-v1")
        val carts = Database.strings("store", "SELECT cart_id::text FROM carts WHERE store_id IN ('S-1','S-2')").toSet() - initialCarts
        for (cart in carts) {
            Database.update("store", "DELETE FROM store_outbox WHERE submission_id IN (SELECT submission_id FROM submissions WHERE cart_id=?::uuid)", cart)
            Database.update("store", "DELETE FROM stock_expenses WHERE submission_id IN (SELECT submission_id FROM submissions WHERE cart_id=?::uuid)", cart)
            for (table in listOf("submissions", "cart_items", "carts")) Database.update("store", "DELETE FROM $table WHERE cart_id=?::uuid", cart)
        }
        for (product in products) {
            val deliveries = Database.strings("warehouse", "SELECT delivery_id FROM delivery_items WHERE product_id=?", product)
            Database.update("store", "DELETE FROM stock_movements WHERE stock_item_id IN (SELECT stock_item_id FROM inventory WHERE product_id=?)", product)
            Database.update("store", "DELETE FROM inventory WHERE product_id=?", product)
            for (delivery in deliveries) {
                for (table in listOf("processed_events", "stock_receipts")) Database.update("store", "DELETE FROM $table WHERE delivery_id=?", delivery)
                for (table in listOf("warehouse_outbox", "received_events", "delivery_items", "deliveries")) Database.update("warehouse", "DELETE FROM $table WHERE delivery_id=?", delivery)
            }
        }
    }
}

/** Explicit function template owns all UI resources; reverse cleanup preserves a primary assertion with suppressed cleanup failures. */
fun withBrowserTemplate(body: (BrowserScenario) -> Unit) {
    withShopTemplate { resources ->
        SelenideLogger.addListener("allure", AllureSelenide().includeSelenideSteps(true))
        resources.onClose("Remove scenario Selenide listener") { SelenideLogger.removeListener<AllureSelenide>("allure") }
        body(BrowserScenario(resources))
    }
}
