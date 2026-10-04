package org.golenev.commondto

/** Позиция каталога STORE. Денежные значения сохраняются в виде десятичных строк контракта. */
data class Stock(val stockItemId: String, val productId: String, val productType: String,
                 val shortName: String, val description: String, val unitPrice: String,
                 val currency: String, val availableQuantity: Int)
