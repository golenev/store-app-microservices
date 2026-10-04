package org.golenev.db.tables.inventory

import java.util.*

/** Снимок идентификаторов и количества одной позиции остатка для явной проверки баланса в тесте. */
data class InventoryRow(val stockItemId: UUID, val storeId: String, val productId: String, val availableQuantity: Int)
