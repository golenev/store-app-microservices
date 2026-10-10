package com.shop.warehouse.dto;

/** Безопасный код и пояснение ошибки расчёта или содержимого поставки. */
public record Failure(String code, String message) { }
