package models

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class ProductPayload(
    var barcodeId: Long? = null,
    var shortName: String? = null,
    var description: String? = null,
    var price: BigDecimal = BigDecimal.ZERO,
    var quantity: Int = 0,
    var addedAtTariffs: String? = null,
    @field:JsonProperty("isFoodstuff")
    @get:JsonProperty("isFoodstuff")
    var isFoodstuff: Boolean = false
)

