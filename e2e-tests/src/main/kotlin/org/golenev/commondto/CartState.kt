package org.golenev.commondto

/** Допустимые состояния корзины v1. Неизвестное состояние сервера считается ошибкой разбора, а не пустой корзиной. */
enum class CartState { OPEN, SUBMITTED }
