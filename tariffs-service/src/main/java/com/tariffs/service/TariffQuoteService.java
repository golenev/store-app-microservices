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

/**
 * Возвращает сохранённую наценку или рассчитывает её по текущим правилам PostgreSQL. Обращения к Redis
 * выполняет вне транзакции чтения правил.
 */
@Service
public class TariffQuoteService {
    private static final Logger log = LoggerFactory.getLogger(TariffQuoteService.class);
    private final TariffRuleService rules;
    private final TariffQuoteCache cache;
    private final Clock clock;

    /**
     * Подключает выбор тарифного правила, кеш расчётов и часы для времени сброса.
     *
     * @param rules управление тарифными правилами и выбор правила для расчёта
     * @param cache чтение и сохранение расчётов в Redis
     * @param clock часы для дат операций и сроков фоновых попыток
     */
    public TariffQuoteService(TariffRuleService rules, TariffQuoteCache cache, Clock clock) {
        this.rules = rules;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Проверяет параметры расчёта, затем ищет результат в Redis. Если результата нет, выбирает правило в
     * PostgreSQL и пытается сохранить успешный расчёт. Ошибки выбора правила в кеш не записывает;
     * недоступность Redis не мешает получить результат из БД.
     *
     * @param request тип товара, город, валюта и закупочная цена для выбора тарифа
     * @return наценка, UUID и версия выбранного тарифного правила
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
     * Удаляет сохранённые расчёты и возвращает имя кеша и время успешного сброса в UTC. При ошибке Redis
     * передаёт её вызывающему коду вместо сообщения об успехе.
     *
     * @return имя кеша и время успешного сброса
     */
    public CacheResetResponse reset() {
        cache.reset();
        return new CacheResetResponse("tariff-quotes", clock.instant());
    }

    /**
     * Каждый день в полночь по времени Москвы сбрасывает кеш тем же способом, что ручной запрос API. При сбое
     * Redis пишет ошибку в журнал.
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
