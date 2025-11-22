package com.store.e2etest.tests

import com.store.e2etest.db.TariffsTable
import com.store.e2etest.db.dbTariffsExec
import com.store.e2etest.models.TariffPayload
import com.store.e2etest.models.TariffResponse
import com.store.e2etest.service.TariffsService
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TariffsE2ETest {

    private val service = TariffsService()

    @BeforeEach
    fun cleanTariffsTable() {
        dbTariffsExec { TariffsTable.deleteAll() }
    }

    @Test
    @DisplayName("Создание тарифа через API и чтение из БД")
    fun `create tariff and fetch it`() {
        val request = TariffPayload(
            productType = "electronics",
            markupCoefficient = BigDecimal("1.15")
        )

        val createdTariff: TariffResponse = service.createTariff(request)
        createdTariff shouldBe TariffResponse(
            productType = request.productType,
            markupCoefficient = request.markupCoefficient
        )

        val tariffs = service.getTariffs()
        tariffs.shouldContain(createdTariff)
    }
}
