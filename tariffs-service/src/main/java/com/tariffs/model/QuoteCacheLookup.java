package com.tariffs.model;

import com.tariffs.dto.QuoteResponse;

/**
 * Результат чтения Redis и идентификатор поколения кеша. Отсутствие расчёта требует чтения PostgreSQL;
 * отсутствие поколения запрещает записывать результат в Redis.
 *
 * @param generation идентификатор поколения кеша; {@code null} запрещает запись результата
 * @param quote рассчитанная наценка, UUID и версия выбранного правила
 */
public record QuoteCacheLookup(String generation, QuoteResponse quote) { }
