package com.tariffs.controller;

import com.tariffs.api.TariffModels.*;
import com.tariffs.api.TariffApiException;
import com.tariffs.service.TariffRuleService;
import com.tariffs.service.TariffQuoteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

/** Public v1 tariff contract; no legacy percentage DTOs escape through these endpoints. */
@RestController
@RequestMapping("/tariffs")
public class TariffRuleController {
    private final TariffRuleService rules;
    private final TariffQuoteService quotes;

    /** Receives independent DB rule and cache/quote services. */
    public TariffRuleController(TariffRuleService rules, TariffQuoteService quotes) {
        this.rules = rules;
        this.quotes = quotes;
    }

    /** Selects the fractional quote from validated query parameters; missing dimensions are HTTP 400. */
    @GetMapping("/quote")
    public QuoteResponse quote(@RequestParam String productType, @RequestParam String purchasePrice,
                               @RequestParam String currency, @RequestParam String cityId) {
        return quotes.quote(new QuoteRequest(productType, purchasePrice, currency, cityId));
    }

    /** Returns current database rules without reading or invalidating the quote cache. */
    @GetMapping("/rules")
    public RulesResponse list() { return rules.list(); }

    /** Reads one UUID rule or produces the standard NOT_FOUND error. */
    @GetMapping("/rules/{tariffRuleId}")
    public Rule get(@PathVariable String tariffRuleId) { return rules.get(parseId(tariffRuleId)); }

    /** Validates and creates a rule, returning HTTP 201, its server UUID/version and Location. */
    @PostMapping("/rules")
    public ResponseEntity<Rule> create(@Valid @RequestBody RuleRequest request) {
        Rule result = rules.create(request);
        return ResponseEntity.created(URI.create("/tariffs/rules/" + result.tariffRuleId())).body(result);
    }

    /** Fully replaces a rule and increments version without invalidating filled quotes. */
    @PutMapping("/rules/{tariffRuleId}")
    public Rule update(@PathVariable String tariffRuleId, @Valid @RequestBody RuleRequest request) {
        return rules.update(parseId(tariffRuleId), request);
    }

    /** Removes a rule and returns HTTP 204; its cached quote remains available until reset. */
    @DeleteMapping("/rules/{tariffRuleId}")
    public ResponseEntity<Void> delete(@PathVariable String tariffRuleId) {
        rules.delete(parseId(tariffRuleId));
        return ResponseEntity.noContent().build();
    }

    /** Atomically clears only quote snapshots and returns an UTC timestamp, or HTTP 503 when Redis fails. */
    @PostMapping("/cache/reset")
    public CacheResetResponse reset() { return quotes.reset(); }

    /** Rejects abbreviated UUIDs that Java's lenient parser accepts, keeping the exact v1 identifier shape. */
    private UUID parseId(String value) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new TariffApiException(400, "VALIDATION_ERROR", "Invalid tariffRuleId");
        return UUID.fromString(value);
    }
}
