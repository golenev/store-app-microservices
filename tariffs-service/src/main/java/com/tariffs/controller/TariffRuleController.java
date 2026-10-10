package com.tariffs.controller;

import com.tariffs.dto.CacheResetResponse;
import com.tariffs.dto.QuoteRequest;
import com.tariffs.dto.QuoteResponse;
import com.tariffs.dto.RuleRequest;
import com.tariffs.dto.RulesResponse;
import com.tariffs.exception.TariffApiException;
import com.tariffs.model.Rule;

import com.tariffs.service.TariffRuleService;
import com.tariffs.service.TariffQuoteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

/** Предоставляет публичный HTTP-контракт правил, расчёта и сброса кеша тарифов. */
@RestController
@RequestMapping("/tariffs")
public class TariffRuleController {
    private final TariffRuleService rules;
    private final TariffQuoteService quotes;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param rules сервис транзакционных операций тарифных правил
     * @param quotes сервис расчёта и сброса кеша
     */
    public TariffRuleController(TariffRuleService rules, TariffQuoteService quotes) {
        this.rules = rules;
        this.quotes = quotes;
    }

    /**
     * Возвращает дробную наценку по параметрам продукта, цены, валюты и города; отсутствие обязательного
     * параметра вызывает 400.
     *
     * @param productType тип продукта FOOD или NON_FOOD
     * @param purchasePrice закупочная цена в строковом денежном формате
     * @param currency валюта денежного значения
     * @param cityId идентификатор города выбора тарифа
     */
    @GetMapping("/quote")
    public QuoteResponse quote(@RequestParam String productType, @RequestParam String purchasePrice,
                               @RequestParam String currency, @RequestParam String cityId) {
        return quotes.quote(new QuoteRequest(productType, purchasePrice, currency, cityId));
    }

    /**
     * Возвращает актуальные правила PostgreSQL без чтения или сброса кеша расчётов.
     */
    @GetMapping("/rules")
    public RulesResponse list() { return rules.list(); }

    /**
     * Возвращает правило tariffRuleId; неверный UUID вызывает VALIDATION_ERROR, отсутствие правила — NOT_FOUND.
     *
     * @param tariffRuleId идентификатор правила из HTTP-маршрута
     */
    @GetMapping("/rules/{tariffRuleId}")
    public Rule get(@PathVariable String tariffRuleId) { return rules.get(parseId(tariffRuleId)); }

    /**
     * Проверяет request и создаёт правило; возвращает 201 с назначенными UUID, версией и Location.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @PostMapping("/rules")
    public ResponseEntity<Rule> create(@Valid @RequestBody RuleRequest request) {
        Rule result = rules.create(request);
        return ResponseEntity.created(URI.create("/tariffs/rules/" + result.tariffRuleId())).body(result);
    }

    /**
     * Полностью заменяет tariffRuleId данными request и увеличивает версию; заполненный кеш не сбрасывает.
     *
     * @param tariffRuleId идентификатор правила из HTTP-маршрута
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    @PutMapping("/rules/{tariffRuleId}")
    public Rule update(@PathVariable String tariffRuleId, @Valid @RequestBody RuleRequest request) {
        return rules.update(parseId(tariffRuleId), request);
    }

    /**
     * Удаляет tariffRuleId и возвращает 204; сохранённый результат расчёта остаётся до сброса кеша.
     *
     * @param tariffRuleId идентификатор правила из HTTP-маршрута
     * @return HTTP-ответ с описанным статусом и телом
     */
    @DeleteMapping("/rules/{tariffRuleId}")
    public ResponseEntity<Void> delete(@PathVariable String tariffRuleId) {
        rules.delete(parseId(tariffRuleId));
        return ResponseEntity.noContent().build();
    }

    /**
     * Атомарно сбрасывает кеш расчётов и возвращает UTC-время; недоступный Redis вызывает 503.
     */
    @PostMapping("/cache/reset")
    public CacheResetResponse reset() { return quotes.reset(); }

    /**
     * Разбирает только полный UUID value; сокращённые представления отклоняет как VALIDATION_ERROR.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private UUID parseId(String value) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new TariffApiException(400, "VALIDATION_ERROR", "Invalid tariffRuleId");
        return UUID.fromString(value);
    }
}
