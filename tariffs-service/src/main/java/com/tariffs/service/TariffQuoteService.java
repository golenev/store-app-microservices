package com.tariffs.service;

import com.tariffs.api.TariffModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;

/** Coordinates cache and committed DB rule reads without wrapping Redis I/O in a database transaction. */
@Service
public class TariffQuoteService {
    private static final Logger log = LoggerFactory.getLogger(TariffQuoteService.class);
    private final TariffRuleService rules;
    private final TariffQuoteCache cache;
    private final Clock clock;

    /** Receives rule transactions, Redis snapshots and an injectable UTC clock for reset responses. */
    public TariffQuoteService(TariffRuleService rules, TariffQuoteCache cache, Clock clock) {
        this.rules = rules;
        this.cache = cache;
        this.clock = clock;
    }

    /** Returns a cached snapshot or one exact DB match; business failures are never cached as zero rates. */
    public QuoteResponse quote(QuoteRequest request) {
        rules.validateQuote(request);
        TariffQuoteCache.Lookup lookup = cache.read(request);
        if (lookup.quote() != null) return lookup.quote();
        QuoteResponse result = rules.calculate(request);
        cache.write(request, lookup, result);
        return result;
    }

    /** Resets only the new quote namespace; Redis failure propagates instead of returning a false success. */
    public CacheResetResponse reset() {
        cache.reset();
        return new CacheResetResponse("tariff-quotes", clock.instant());
    }

    /** At Moscow midnight runs the same atomic reset as HTTP; an unavailable Redis is logged for diagnosis. */
    @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Moscow")
    public void scheduledReset() {
        try {
            reset();
        } catch (DataAccessException exception) {
            log.error("Scheduled tariff quote reset failed: Redis unavailable");
        }
    }
}
