package org.golenev.commondto
import com.fasterxml.jackson.annotation.JsonInclude

/** Позиция приёмки. До успешного расчёта поля наценки и продажной цены отсутствуют. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PricedLine(val lineId: String, val productId: String, val productType: String,
                      val shortName: String, val description: String, val quantity: Int,
                      val purchasePrice: String, val currency: String, val markupRate: String? = null,
                      val tariffRuleId: String? = null, val tariffVersion: Long? = null, val salePrice: String? = null)
