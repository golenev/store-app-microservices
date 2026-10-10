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

/** Хранит снимки расчётов без TTL; Lua защищает от заполнения прежним результатом после сброса. */
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
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param redis подключение Redis для хранения снимков расчётов
     * @param mapper ObjectMapper приложения для согласованного JSON
     */
    public TariffQuoteCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * Атомарно читает поколение и снимок request из Redis. Недоступность или повреждённый JSON возвращает
     * промах; null generation запрещает заполнение.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
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
     * Сохраняет успешный quote только в поколении исходного lookup; ошибка Redis не отменяет расчёт, ошибка
     * сериализации вызывает IllegalStateException.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @param lookup результат чтения кеша с поколением сброса
     * @param quote успешный результат расчёта тарифа
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
     * Атомарно меняет поколение и удаляет только записи расчётов; недоступность Redis распространяется для HTTP
     * 503.
     */
    public void reset() { redis.execute(RESET, List.of(EPOCH_KEY, ENTRIES_KEY), UUID.randomUUID().toString()); }

    /**
     * Строит однозначный ключ из проверенных измерений request и нормализованной цены; cityId не допускает
     * символ |.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    private String key(QuoteRequest request) {
        return request.productType() + "|" + request.cityId() + "|" + request.currency() + "|"
                + new BigDecimal(request.purchasePrice()).setScale(2).toPlainString();
    }
}
