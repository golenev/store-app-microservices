package com.tariffs.repository;

import com.tariffs.dto.QuoteRequest;
import com.tariffs.dto.QuoteResponse;
import com.tariffs.model.QuoteCacheLookup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Хранит результаты расчёта наценки в Redis до явного или планового сброса. Идентификатор поколения
 * меняется при сбросе и не позволяет сохранить расчёт, начатый до него.
 */
@Component
public class TariffQuoteCache {
    public static final String ENTRIES_KEY = "tariff-quotes:v1:entries";
    public static final String EPOCH_KEY = "tariff-quotes:v1:epoch";
    private static final Logger log = LoggerFactory.getLogger(TariffQuoteCache.class);
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
            local epoch=redis.call('GET',KEYS[1])
            if not epoch then epoch=ARGV[2]; redis.call('SET',KEYS[1],epoch) end
            return {epoch,redis.call('HGET',KEYS[2],ARGV[1]) or ''}
            """, List.class);
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            if (redis.call('GET',KEYS[1]) or '') ~= ARGV[1] then return 0 end
            redis.call('HSET',KEYS[2],ARGV[2],ARGV[3]); return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RESET = new DefaultRedisScript<>("""
            redis.call('SET',KEYS[1],ARGV[1]); redis.call('DEL',KEYS[2]); return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /**
     * Подключает Redis и преобразование результатов расчёта в JSON.
     *
     * @param redis выполнение операций Redis с сохранёнными расчётами
     * @param mapper настройки преобразования Java-объектов и JSON
     */
    public TariffQuoteCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * Одной операцией Redis читает результат для заданных условий и идентификатор поколения кеша. Если записи
     * нет или её JSON повреждён, возвращает отсутствие расчёта. При недоступном Redis возвращает также
     * отсутствие поколения: такой результат нельзя записать обратно в кеш.
     *
     * @param request тип товара, город, валюта и закупочная цена для выбора тарифа
     * @return результат чтения расчёта и поколение кеша
     */
    public QuoteCacheLookup read(QuoteRequest request) {
        try {
            List<?> result = redis.execute(READ, List.of(EPOCH_KEY, ENTRIES_KEY), key(request), UUID.randomUUID().toString());
            String generation = (String) result.get(0);
            String json = (String) result.get(1);
            if (json.isEmpty()) return new QuoteCacheLookup(generation, null);
            try {
                return new QuoteCacheLookup(generation, mapper.readValue(json, QuoteResponse.class));
            } catch (JsonProcessingException exception) {
                log.warn("Malformed tariff snapshot; recalculating through PostgreSQL");
                return new QuoteCacheLookup(generation, null);
            }
        } catch (DataAccessException exception) {
            log.warn("Quote cache unavailable; using PostgreSQL");
            return new QuoteCacheLookup(null, null);
        }
    }

    /**
     * Сохраняет успешный расчёт, только если поколение кеша со времени чтения не изменилось. Если поколение
     * неизвестно, ничего не записывает. Сбой Redis оставляет вычисленный результат доступным клиенту;
     * невозможность записать JSON вызывает {@code IllegalStateException}.
     *
     * @param request тип товара, город, валюта и закупочная цена для выбора тарифа
     * @param lookup результат предыдущего чтения и поколение кеша
     * @param quote рассчитанная наценка, UUID и версия выбранного правила
     */
    public void write(QuoteRequest request, QuoteCacheLookup lookup, QuoteResponse quote) {
        if (lookup.generation() == null) return;
        try {
            redis.execute(WRITE, List.of(EPOCH_KEY, ENTRIES_KEY), lookup.generation(), key(request), mapper.writeValueAsString(quote));
        } catch (DataAccessException exception) {
            log.warn("Quote cache write failed; returning the PostgreSQL result");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize tariff quote", exception);
        }
    }

    /**
     * Одной операцией Redis меняет поколение кеша и удаляет все сохранённые расчёты этого сервиса. Правила
     * PostgreSQL не меняет. Ошибка Redis передаётся вызывающему коду, поэтому API не сообщит об успешном
     * сбросе.
     */
    public void reset() { redis.execute(RESET, List.of(EPOCH_KEY, ENTRIES_KEY), UUID.randomUUID().toString()); }

    /**
     * Собирает ключ кеша из проверенных типа товара, города, валюты и цены с двумя знаками после точки. Символ
     * {@code |} разделяет поля; проверка идентификатора города должна исключать этот символ.
     *
     * @param request тип товара, город, валюта и закупочная цена для выбора тарифа
     * @return ключ кеша для заданных условий расчёта
     */
    private String key(QuoteRequest request) {
        return request.productType() + "|" + request.cityId() + "|" + request.currency() + "|"
                + new BigDecimal(request.purchasePrice()).setScale(2).toPlainString();
    }
}
