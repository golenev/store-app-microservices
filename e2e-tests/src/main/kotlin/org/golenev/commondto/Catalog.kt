package org.golenev.commondto

/** Каталог конкретного магазина. Контракт не гарантирует порядок позиций. */
data class Catalog(val storeId: String, val items: List<Stock>)
