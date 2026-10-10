package com.shop.store.model;

import com.shop.store.dto.Stock;

/** Заблокированная позиция остатка и порядок последней применённой цены. */
public record ExistingStock(Stock stock, long sequence) { }
