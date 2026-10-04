package org.golenev.commondto
import com.fasterxml.jackson.annotation.JsonInclude

/** Позиция приёмки. До успешного расчёта поля наценки и продажной цены отсутствуют. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PricedLine(
    /**
     * Идентификатор позиции внутри поставки.
     */
    val lineId: String,
    /**
     * Идентификатор товара, связывающий поставку, остаток и корзину.
     */
    val productId: String,
    /**
     * Тип товара, используемый при выборе тарифного правила.
     */
    val productType: String,
    /**
     * Краткое название товара для отображения пользователю.
     */
    val shortName: String,
    /**
     * Описание товара, переданное поставщиком.
     */
    val description: String,
    /**
     * Количество единиц товара в позиции.
     */
    val quantity: Int,
    /**
     * Закупочная цена одной единицы товара в виде точной десятичной строки.
     */
    val purchasePrice: String,
    /**
     * Код валюты денежных значений, например RUB.
     */
    val currency: String,
    /**
     * Коэффициент наценки, использованный при расчёте цены; null до успешного расчёта.
     */
    val markupRate: String? = null,
    /**
     * Идентификатор использованного тарифного правила; null до успешного расчёта.
     */
    val tariffRuleId: String? = null,
    /**
     * Версия использованного тарифного правила; null до успешного расчёта.
     */
    val tariffVersion: Long? = null,
    /**
     * Рассчитанная продажная цена единицы товара; null до успешного расчёта.
     */
    val salePrice: String? = null
)
