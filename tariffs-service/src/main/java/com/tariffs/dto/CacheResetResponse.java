package com.tariffs.dto;

import java.time.Instant;

/**
 * Ответ об успешном сбросе: имя кеша и время завершения операции в UTC.
 *
 * @param cache имя сброшенного кеша
 * @param resetAt время успешного сброса в UTC
 */
public record CacheResetResponse(String cache, Instant resetAt) { }
