package com.shop.warehouse.messaging.dto;

/** Проверенная строка входящей поставки до расчёта тарифа и продажной цены. */
public record InputLine(String lineId, String productId, String productType, String shortName,
                            String description, int quantity, String purchasePrice, String currency) { }
