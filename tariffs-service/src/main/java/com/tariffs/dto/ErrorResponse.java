package com.tariffs.dto;

import java.time.Instant;
import java.util.List;

/** Безопасный HTTP-ответ ошибки с UTC-временем, статусом, кодом и путём запроса. */
public record ErrorResponse(Instant timestamp, int status, String code, String message,
                                String path, List<ErrorDetail> details) { }
