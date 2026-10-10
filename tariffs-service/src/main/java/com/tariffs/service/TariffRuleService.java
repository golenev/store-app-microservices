package com.tariffs.service;

import com.tariffs.dto.ErrorDetail;
import com.tariffs.dto.QuoteRequest;
import com.tariffs.dto.QuoteResponse;
import com.tariffs.dto.RuleRequest;
import com.tariffs.dto.RulesResponse;
import com.tariffs.exception.TariffApiException;
import com.tariffs.model.Rule;

import com.tariffs.repository.TariffRuleRepository;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Управляет правилами PostgreSQL и бизнес-проверками; CRUD не сбрасывает заполненный кеш расчётов. */
@Service
public class TariffRuleService {
    private final TariffRuleRepository repository;
    private final Validator validator;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param repository репозиторий, участвующий в транзакциях сервиса
     * @param validator Bean Validation для публичных параметров контракта
     */
    public TariffRuleService(TariffRuleRepository repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * Возвращает правила PostgreSQL в транзакции чтения; превышение 1000 строк вызывает DEPENDENCY_UNAVAILABLE.
     */
    @Transactional(readOnly = true)
    public RulesResponse list() {
        List<Rule> rules = repository.list();
        if (rules.size() > 1000) throw new TariffApiException(503, "DEPENDENCY_UNAVAILABLE", "Rule catalog exceeds the v1 limit");
        return new RulesResponse(rules);
    }

    /**
     * Читает правило id в транзакции чтения без обращения к кешу; отсутствие вызывает NOT_FOUND.
     *
     * @param id UUID запрашиваемого объекта
     */
    @Transactional(readOnly = true)
    public Rule get(UUID id) { return repository.find(id).orElseThrow(this::notFound); }

    /**
     * Проверяет request и создаёт правило версии 1 под общей блокировкой каталога в одной транзакции;
     * пересечения интервалов разрешены и выявляются при расчёте.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    @Transactional
    public Rule create(RuleRequest request) {
        validateRule(request);
        repository.lockCatalog();
        if (repository.count() >= 1000) throw new TariffApiException(400, "VALIDATION_ERROR", "At most 1000 rules are supported");
        UUID id = UUID.randomUUID();
        repository.insert(id, request);
        return get(id);
    }

    /**
     * В одной транзакции блокирует id и заменяет его данными request с увеличением версии; заполненный кеш не
     * сбрасывает.
     *
     * @param id UUID запрашиваемого объекта
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    @Transactional
    public Rule update(UUID id, RuleRequest request) {
        validateRule(request);
        Rule previous = repository.lock(id).orElseThrow(this::notFound);
        if (previous.version() == 9007199254740991L) throw new TariffApiException(400, "VALIDATION_ERROR", "Rule version is exhausted");
        repository.replace(id, request);
        return get(id);
    }

    /**
     * Удаляет только правило id в транзакции; отсутствие вызывает NOT_FOUND, прежний кешированный результат
     * сохраняется.
     *
     * @param id UUID запрашиваемого объекта
     */
    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) throw notFound();
    }

    /**
     * Выбирает ровно одно правило для request на интервале [lowerBound, upperBound). Отсутствие вызывает
     * TARIFF_NOT_FOUND, пересечение — TARIFF_AMBIGUOUS; нулевую наценку вместо ошибки не возвращает.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    @Transactional(readOnly = true)
    public QuoteResponse calculate(QuoteRequest request) {
        List<Rule> matches = repository.matching(request.productType(), request.cityId(), request.currency(), new BigDecimal(request.purchasePrice()));
        if (matches.isEmpty()) throw new TariffApiException(404, "TARIFF_NOT_FOUND", "No tariff rule matches the quote");
        if (matches.size() > 1) throw new TariffApiException(409, "TARIFF_AMBIGUOUS", "Multiple tariff rules match the quote");
        Rule rule = matches.getFirst();
        return new QuoteResponse(rule.markupRate(), rule.tariffRuleId(), rule.version());
    }

    /**
     * Проверяет request и положительную цену до Redis или SQL; нарушение вызывает VALIDATION_ERROR.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    public void validateQuote(QuoteRequest request) {
        validate(request);
        if (new BigDecimal(request.purchasePrice()).signum() <= 0)
            throw new TariffApiException(400, "VALIDATION_ERROR", "purchasePrice must be positive");
    }

    /**
     * Проверяет поля request и lowerBound < upperBound при заданном верхнем пределе до записи в БД.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    private void validateRule(RuleRequest request) {
        validate(request);
        if (request.upperBound() != null && new BigDecimal(request.lowerBound()).compareTo(new BigDecimal(request.upperBound())) >= 0)
            throw new TariffApiException(400, "VALIDATION_ERROR", "lowerBound must be less than upperBound");
    }

    /**
     * Проверяет request через Bean Validation и формирует упорядоченные безопасные ошибки полей; null и
     * нарушения вызывают VALIDATION_ERROR.
     *
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    private void validate(Object request) {
        if (request == null) throw new TariffApiException(400, "VALIDATION_ERROR", "Request is required");
        List<ErrorDetail> details = validator.validate(request).stream()
                .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message)).toList();
        if (!details.isEmpty()) throw new TariffApiException(400, "VALIDATION_ERROR", "Invalid tariff request", details);
    }

    /**
     * Возвращает безопасную ошибку NOT_FOUND без SQL и входного идентификатора в пояснении.
     */
    private TariffApiException notFound() { return new TariffApiException(404, "NOT_FOUND", "Tariff rule not found"); }
}
