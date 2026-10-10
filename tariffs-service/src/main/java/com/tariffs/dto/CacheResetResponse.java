package com.tariffs.dto;

import java.time.Instant;

/** Имя сброшенного кеша и UTC-время успешного сброса. */
public record CacheResetResponse(String cache, Instant resetAt) { }
