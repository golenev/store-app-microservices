package org.golenev.ui.config

import com.codeborne.selenide.Configuration
import com.codeborne.selenide.logevents.LogEventListener
import com.codeborne.selenide.logevents.SelenideLogger
import org.golenev.ui.allure.ReadableAllureSelenideListener
import org.golenev.ui.allure.UiElementNameRegistry
import org.openqa.selenium.chrome.ChromeOptions
import java.io.File

/** Конфигурация Selenide по образцу golenev-xlsx-report-system; значения адреса и артефактов задаёт изолированный запуск. */
class DriverConfig {
    /** Настраивает текущий последовательный UI-сценарий и устанавливает читаемый обработчик Allure. Мобильная эмуляция задаёт ширину 390 CSS-пикселей. */
    fun setup(baseUrl: String = org.golenev.config.Environment.STORE_URL, mobile: Boolean = false) {
        Configuration.browser = "chrome"
        Configuration.browserSize = "1280x900"
        Configuration.timeout = 30_000
        Configuration.fastSetValue = true
        Configuration.pageLoadStrategy = "normal"
        Configuration.headless = true
        Configuration.screenshots = true
        Configuration.proxyEnabled = true
        Configuration.baseUrl = baseUrl
        Configuration.reportsFolder = File(System.getenv("E2E_ALLURE_RESULTS") ?: "build/allure-results")
            .parentFile.resolve("browser-evidence").absolutePath
        UiElementNameRegistry.clear()
        SelenideLogger.removeListener<LogEventListener>("AllureSelenide")
        SelenideLogger.removeListener<LogEventListener>("ReadableAllureSelenide")
        SelenideLogger.addListener("ReadableAllureSelenide", ReadableAllureSelenideListener())
        Configuration.browserCapabilities = getChromeOptions(mobile)
    }

    /** Формирует параметры собственного Chrome; бинарник и драйвер при необходимости передаются окружением запуска. */
    fun getChromeOptions(mobile: Boolean): ChromeOptions {
        val options = ChromeOptions().addArguments("--headless=new", "--window-size=1280,900", "--no-sandbox",
            "--disable-dev-shm-usage", "--disable-notifications", "--proxy-bypass-list=<-loopback>")
        System.getenv("E2E_CHROME_BINARY")?.let { options.setBinary(it) }
        System.getenv("E2E_CHROME_DRIVER")?.let { System.setProperty("webdriver.chrome.driver", it) }
        if (mobile) options.setExperimentalOption("mobileEmulation", mapOf("deviceMetrics" to
            mapOf("width" to 390, "height" to 844, "pixelRatio" to 1)))
        return options
    }
}
