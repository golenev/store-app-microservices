package com.store.e2etest.service

import com.store.e2etest.http.Paths
import com.store.e2etest.http.RequestExecutor
import com.store.e2etest.models.TariffPayload
import com.store.e2etest.models.TariffResponse
import io.qameta.allure.Step

class TariffsService : RequestExecutor<TariffResponse>(
    path = Paths.TARIFFS.path,
) {

    private fun tariffRequest() = request()

    @Step("Создаем тариф для продукта {request.productType}")
    fun createTariff(request: TariffPayload): TariffResponse =
        postRequest(path, tariffRequest().body(request)).`as`(TariffResponse::class.java)

    @Step("Получаем список всех тарифов")
    fun getTariffs(): List<TariffResponse> = getRequest(
        path,
        tariffRequest().queryParam("all", true)
    ).jsonPath().getList("", TariffResponse::class.java)
}
