package com.tariffs.model;

import com.tariffs.dto.QuoteResponse;

/** Результат чтения кеша и поколение сброса; null generation запрещает заполнение кеша. */
public record QuoteCacheLookup(String generation, QuoteResponse quote) { }
