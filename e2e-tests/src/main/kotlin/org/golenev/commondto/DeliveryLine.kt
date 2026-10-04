package org.golenev.commondto

/** Позиция поставки по контракту v1. Деньги передаются десятичной строкой; продажную цену рассчитывает сервис, а не поставщик. */
data class DeliveryLine(val lineId: String, val productId: String, val productType: String, val shortName: String,
                        val description: String, val quantity: Int, val purchasePrice: String, val currency: String = "RUB")
