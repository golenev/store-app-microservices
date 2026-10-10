package com.shop.store.dto;

/** Запрос полной замены количества позиции с ожидаемой версией корзины. */
public record PutItem(int quantity, long expectedCartVersion) { }
