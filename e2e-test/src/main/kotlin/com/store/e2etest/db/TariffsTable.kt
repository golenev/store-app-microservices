package com.store.e2etest.db

import org.jetbrains.exposed.sql.Table

object TariffsTable : Table(name = "tariffs") {
    val productType = varchar("product_type", 255)
    val markupCoefficient = decimal("markup_coefficient", precision = 10, scale = 2)

    override val primaryKey = PrimaryKey(productType)
}
