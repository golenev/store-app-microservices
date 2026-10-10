package com.tariffs.service;

import com.tariffs.dto.CacheResetResponse;
import com.tariffs.dto.QuoteRequest;
import com.tariffs.dto.QuoteResponse;
import com.tariffs.model.QuoteCacheLookup;
import com.tariffs.repository.TariffQuoteCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;

/** Координирует кеш и чтение зафиксированных правил без SQL-транзакции вокруг Redis. */
@Service
public class TariffQuoteService {
    private static final Logger log = LoggerFactory.getLogger(TariffQuoteService.class);
    private final TariffRuleService rules;
    private final TariffQuoteCache cache;
    private final Clock clock;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param rules сервис транзакционных операций тарифных правил
     * @param cache репозиторий снимков Redis
     * @param clock общие UTC-часы приложения
     */
    public TariffQuoteService(TariffRuleService rules, TariffQuoteCache cache, Clock clock) {
        this.rules = rules;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Проверяет request, возвращает снимок кеша либо единственный результат PostgreSQL. Бизнес-ошибки не
     * кеширует; Redis выполняется вне SQL-транзакции.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    public QuoteResponse quote(QuoteRequest request) {
        rules.validateQuote(request);
        QuoteCacheLookup lookup = cache.read(request);
        if (lookup.quote() != null) return lookup.quote();
        QuoteResponse result = rules.calculate(request);
        cache.write(request, lookup, result);
        return result;
    }

    /**
     * Атомарно сбрасывает только пространство кеша расчётов и возвращает UTC-время; ошибка Redis
     * распространяется вместо ложного успеха.
     */
    public CacheResetResponse reset() {
        cache.reset();
        return new CacheResetResponse("tariff-quotes", clock.instant());
    }

    /**
     * В полночь Europe/Moscow выполняет тот же сброс, что HTTP; недоступный Redis записывает в журнал.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Moscow")
    public void scheduledReset() {
        try {
            reset();
        } catch (DataAccessException exception) {
            log.error("Scheduled tariff quote reset failed: Redis unavailable");
        }
    }
}
