package com.tariffs.service;

import com.tariffs.api.TariffApiException;
import com.tariffs.api.TariffModels.*;
import com.tariffs.repository.TariffRuleRepository;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Authoritative PostgreSQL rule operations; CRUD does not invalidate filled quote snapshots. */
@Service
public class TariffRuleService {
    private final TariffRuleRepository repository;
    private final Validator validator;

    /** Receives SQL persistence and the same Bean Validation rules used by the HTTP controller. */
    public TariffRuleService(TariffRuleRepository repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /** Reads rules directly from PostgreSQL; an oversized manually corrupted catalog returns a safe error. */
    @Transactional(readOnly = true)
    public RulesResponse list() {
        List<Rule> rules = repository.list();
        if (rules.size() > 1000) throw new TariffApiException(503, "DEPENDENCY_UNAVAILABLE", "Rule catalog exceeds the v1 limit");
        return new RulesResponse(rules);
    }

    /** Reads a UUID-scoped rule or returns NOT_FOUND; quote-cache entries are not consulted. */
    @Transactional(readOnly = true)
    public Rule get(UUID id) { return repository.find(id).orElseThrow(this::notFound); }

    /** Creates version 1 under a database-wide creation lock; overlaps remain visible as quote ambiguity. */
    @Transactional
    public Rule create(RuleRequest request) {
        validateRule(request);
        repository.lockCatalog();
        if (repository.count() >= 1000) throw new TariffApiException(400, "VALIDATION_ERROR", "At most 1000 rules are supported");
        UUID id = UUID.randomUUID();
        repository.insert(id, request);
        return get(id);
    }

    /** Locks and fully replaces a rule, incrementing version atomically without clearing the cache. */
    @Transactional
    public Rule update(UUID id, RuleRequest request) {
        validateRule(request);
        Rule previous = repository.lock(id).orElseThrow(this::notFound);
        if (previous.version() == 9007199254740991L) throw new TariffApiException(400, "VALIDATION_ERROR", "Rule version is exhausted");
        repository.replace(id, request);
        return get(id);
    }

    /** Removes only the identified rule; previously cached quotes still refer to its immutable snapshot. */
    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) throw notFound();
    }

    /** Selects exactly one [lower, upper) rule; missing or overlapping rules never become zero markup. */
    @Transactional(readOnly = true)
    public QuoteResponse calculate(QuoteRequest request) {
        List<Rule> matches = repository.matching(request.productType(), request.cityId(), request.currency(), new BigDecimal(request.purchasePrice()));
        if (matches.isEmpty()) throw new TariffApiException(404, "TARIFF_NOT_FOUND", "No tariff rule matches the quote");
        if (matches.size() > 1) throw new TariffApiException(409, "TARIFF_AMBIGUOUS", "Multiple tariff rules match the quote");
        Rule rule = matches.getFirst();
        return new QuoteResponse(rule.markupRate(), rule.tariffRuleId(), rule.version());
    }

    /** Validates identifiers and exact price syntax before any Redis or SQL access. */
    public void validateQuote(QuoteRequest request) {
        validate(request);
        if (new BigDecimal(request.purchasePrice()).signum() <= 0)
            throw new TariffApiException(400, "VALIDATION_ERROR", "purchasePrice must be positive");
    }

    /** Validates all wire fields and the cross-field bound invariant before database writes. */
    private void validateRule(RuleRequest request) {
        validate(request);
        if (request.upperBound() != null && new BigDecimal(request.lowerBound()).compareTo(new BigDecimal(request.upperBound())) >= 0)
            throw new TariffApiException(400, "VALIDATION_ERROR", "lowerBound must be less than upperBound");
    }

    /** Produces deterministic safe field details for any DTO that violates the public validation contract. */
    private void validate(Object request) {
        if (request == null) throw new TariffApiException(400, "VALIDATION_ERROR", "Request is required");
        List<ErrorDetail> details = validator.validate(request).stream()
                .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message)).toList();
        if (!details.isEmpty()) throw new TariffApiException(400, "VALIDATION_ERROR", "Invalid tariff request", details);
    }

    /** Creates a safe missing-rule failure with no SQL or supplied identifier reflected in the message. */
    private TariffApiException notFound() { return new TariffApiException(404, "NOT_FOUND", "Tariff rule not found"); }
}
