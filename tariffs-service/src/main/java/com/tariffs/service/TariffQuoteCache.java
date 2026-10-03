package com.tariffs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tariffs.api.TariffModels.QuoteRequest;
import com.tariffs.api.TariffModels.QuoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Quote snapshots without TTL. Redis Lua prevents an in-flight pre-reset computation from refilling cache. */
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

    /** Captures the reset generation together with a nullable cache snapshot; null generation disables refill. */
    public record Lookup(String generation, QuoteResponse quote) { }

    /** Receives the actual Redis connection and strict DTO serializer; no external work occurs here. */
    public TariffQuoteCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** Atomically reads generation/snapshot; Redis outage or malformed cached JSON becomes a cache miss. */
    public Lookup read(QuoteRequest request) {
        try {
            List<?> result = redis.execute(READ, List.of(EPOCH_KEY, ENTRIES_KEY), key(request), UUID.randomUUID().toString());
            String generation = (String) result.get(0);
            String json = (String) result.get(1);
            if (json.isEmpty()) return new Lookup(generation, null);
            try {
                return new Lookup(generation, mapper.readValue(json, QuoteResponse.class));
            } catch (JsonProcessingException exception) {
                log.warn("Malformed tariff snapshot; recalculating through PostgreSQL");
                return new Lookup(generation, null);
            }
        } catch (DataAccessException exception) {
            log.warn("Quote cache unavailable; using PostgreSQL");
            return new Lookup(null, null);
        }
    }

    /** Caches a successful DB result only in its original generation; write failures do not fail the quote. */
    public void write(QuoteRequest request, Lookup lookup, QuoteResponse quote) {
        if (lookup.generation() == null) return;
        try {
            redis.execute(WRITE, List.of(EPOCH_KEY, ENTRIES_KEY), lookup.generation(), key(request), mapper.writeValueAsString(quote));
        } catch (DataAccessException exception) {
            log.warn("Quote cache write failed; returning the PostgreSQL result");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize tariff quote", exception);
        }
    }

    /** Atomically advances generation and removes only quote entries; outage propagates for a truthful HTTP 503. */
    public void reset() { redis.execute(RESET, List.of(EPOCH_KEY, ENTRIES_KEY), UUID.randomUUID().toString()); }

    /** Builds an unambiguous normalized field from validated dimensions; city identifiers cannot contain '|'. */
    private String key(QuoteRequest request) {
        return request.productType() + "|" + request.cityId() + "|" + request.currency() + "|"
                + new BigDecimal(request.purchasePrice()).setScale(2).toPlainString();
    }
}
