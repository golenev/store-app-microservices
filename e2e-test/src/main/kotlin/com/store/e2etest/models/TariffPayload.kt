package com.store.e2etest.models

import java.math.BigDecimal

data class TariffPayload(
    val productType: String,
    val markupCoefficient: BigDecimal
)

typealias TariffResponse = TariffPayload
