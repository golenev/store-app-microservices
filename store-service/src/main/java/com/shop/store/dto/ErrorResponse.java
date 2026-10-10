package com.shop.store.dto;

import java.time.Instant;

/** Безопасный HTTP-ответ ошибки с UTC-временем, статусом, кодом и путём запроса. */
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path) { }
