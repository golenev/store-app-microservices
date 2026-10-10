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

/**
 * Принимает HTTP-запросы для управления тарифными правилами, расчёта наценки и сброса кеша.
 */
@RestController
@RequestMapping("/tariffs")
public class TariffRuleController {
    private final TariffRuleService rules;
    private final TariffQuoteService quotes;

    /**
     * Подключает операции с тарифными правилами и расчёт наценки.
     *
     * @param rules управление тарифными правилами и выбор правила для расчёта
     * @param quotes расчёт наценки с использованием кеша и его сброс
     */
    public TariffRuleController(TariffRuleService rules, TariffQuoteService quotes) {
        this.rules = rules;
        this.quotes = quotes;
    }

    /**
     * Возвращает наценку, UUID и версию правила для товара, закупочной цены, валюты и города. Отсутствие
     * обязательного параметра приводит к HTTP 400.
     *
     * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
     * @param purchasePrice закупочная цена строкой с двумя знаками после точки
     * @param currency код валюты; в текущем контракте разрешён {@code RUB}
     * @param cityId идентификатор города, для которого выбирается тариф
     * @return наценка, UUID и версия выбранного тарифного правила
     */
    @GetMapping("/quote")
    public QuoteResponse quote(@RequestParam String productType, @RequestParam String purchasePrice,
                               @RequestParam String currency, @RequestParam String cityId) {
        return quotes.quote(new QuoteRequest(productType, purchasePrice, currency, cityId));
    }

    /**
     * Возвращает текущий список правил из PostgreSQL. Кеш расчётов при чтении не используется и не очищается.
     *
     * @return список текущих тарифных правил
     */
    @GetMapping("/rules")
    public RulesResponse list() { return rules.list(); }

    /**
     * Возвращает правило по UUID. Неверный формат идентификатора вызывает {@code VALIDATION_ERROR}, отсутствие
     * правила — {@code NOT_FOUND}.
     *
     * @param tariffRuleId идентификатор тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    @GetMapping("/rules/{tariffRuleId}")
    public Rule get(@PathVariable String tariffRuleId) { return rules.get(parseId(tariffRuleId)); }

    /**
     * Создаёт правило по переданным условиям. Возвращает HTTP 201, созданное правило и его адрес в заголовке
     * {@code Location}; UUID и начальную версию назначает сервер.
     *
     * @param request полный набор условий тарифного правила
     * @return HTTP 201 с созданным правилом и его адресом в {@code Location}
     */
    @PostMapping("/rules")
    public ResponseEntity<Rule> create(@Valid @RequestBody RuleRequest request) {
        Rule result = rules.create(request);
        return ResponseEntity.created(URI.create("/tariffs/rules/" + result.tariffRuleId())).body(result);
    }

    /**
     * Полностью заменяет условия правила и возвращает его новую версию. Сохранённые результаты расчёта в Redis
     * остаются прежними до сброса кеша.
     *
     * @param tariffRuleId идентификатор тарифного правила
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    @PutMapping("/rules/{tariffRuleId}")
    public Rule update(@PathVariable String tariffRuleId, @Valid @RequestBody RuleRequest request) {
        return rules.update(parseId(tariffRuleId), request);
    }

    /**
     * Удаляет правило и возвращает HTTP 204 без тела. Сохранённый результат расчёта по нему остаётся доступным
     * до сброса кеша.
     *
     * @param tariffRuleId идентификатор тарифного правила
     * @return HTTP 204 без тела
     */
    @DeleteMapping("/rules/{tariffRuleId}")
    public ResponseEntity<Void> delete(@PathVariable String tariffRuleId) {
        rules.delete(parseId(tariffRuleId));
        return ResponseEntity.noContent().build();
    }

    /**
     * Сбрасывает кеш расчётов и возвращает время завершения в UTC. Недоступность Redis приводит к HTTP 503.
     *
     * @return имя кеша и время успешного сброса
     */
    @PostMapping("/cache/reset")
    public CacheResetResponse reset() { return quotes.reset(); }

    /**
     * Преобразует строку в UUID только при полном формате из пяти групп шестнадцатеричных цифр. Неверный или
     * сокращённый идентификатор отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value UUID правила из HTTP-маршрута
     * @return UUID, прочитанный из полной строковой записи
     */
    private UUID parseId(String value) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new TariffApiException(400, "VALIDATION_ERROR", "Invalid tariffRuleId");
        return UUID.fromString(value);
    }
}
