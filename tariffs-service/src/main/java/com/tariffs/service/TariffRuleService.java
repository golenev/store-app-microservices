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

/**
 * Создаёт, читает, заменяет и удаляет тарифные правила в PostgreSQL и выбирает правило для расчёта
 * наценки. Изменение правил не очищает ранее сохранённые расчёты в Redis.
 */
@Service
public class TariffRuleService {
    private final TariffRuleRepository repository;
    private final Validator validator;

    /**
     * Подключает хранение правил и проверку обязательных полей и форматов входных данных.
     *
     * @param repository чтение и запись тарифных правил
     * @param validator проверка ограничений, объявленных на полях входных моделей
     */
    public TariffRuleService(TariffRuleRepository repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * Возвращает текущие правила из PostgreSQL. Если их больше 1000, выдаёт {@code DEPENDENCY_UNAVAILABLE},
     * поскольку список превышает лимит ответа API.
     *
     * @return список текущих тарифных правил
     */
    @Transactional(readOnly = true)
    public RulesResponse list() {
        List<Rule> rules = repository.list();
        if (rules.size() > 1000) throw new TariffApiException(503, "DEPENDENCY_UNAVAILABLE", "Rule catalog exceeds the v1 limit");
        return new RulesResponse(rules);
    }

    /**
     * Возвращает правило по UUID из PostgreSQL. Если записи нет, выдаёт {@code NOT_FOUND}; кеш расчётов не
     * используется.
     *
     * @param id UUID тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    @Transactional(readOnly = true)
    public Rule get(UUID id) { return repository.find(id).orElseThrow(this::notFound); }

    /**
     * Проверяет условия и создаёт правило версии 1 с новым UUID. Общая блокировка создания не позволяет
     * одновременным запросам превысить лимит в 1000 правил. Запись и чтение результата выполняет в одной
     * транзакции. Пересечения диапазонов допустимы при создании и считаются ошибкой при выборе тарифа.
     *
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
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
     * Проверяет новые условия и полностью заменяет существующее правило, увеличивая версию на один. До записи
     * блокирует правило до конца транзакции. Отсутствие вызывает {@code NOT_FOUND}, исчерпанная версия —
     * {@code VALIDATION_ERROR}; ранее сохранённые расчёты в Redis остаются прежними.
     *
     * @param id UUID тарифного правила
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
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
     * Удаляет правило в транзакции. Отсутствие записи вызывает {@code NOT_FOUND}; сохранённый расчёт по этому
     * правилу остаётся в Redis до сброса кеша.
     *
     * @param id UUID тарифного правила
     */
    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) throw notFound();
    }

    /**
     * Выбирает единственное правило для проверенных параметров расчёта. Закупочная цена должна быть не меньше
     * нижней границы и строго меньше верхней, если она задана. Возвращает наценку, UUID и версию правила.
     * Отсутствие совпадения вызывает {@code TARIFF_NOT_FOUND}, несколько совпадений — {@code
     * TARIFF_AMBIGUOUS}.
     *
     * @param request тип товара, город, валюта и закупочная цена для выбора тарифа
     * @return наценка, UUID и версия выбранного тарифного правила
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
     * Проверяет обязательные поля, форматы и положительную закупочную цену до обращения к Redis или
     * PostgreSQL. Ошибку данных сообщает как {@code VALIDATION_ERROR}.
     *
     * @param request тип товара, город, валюта и закупочная цена для выбора тарифа
     */
    public void validateQuote(QuoteRequest request) {
        validate(request);
        if (new BigDecimal(request.purchasePrice()).signum() <= 0)
            throw new TariffApiException(400, "VALIDATION_ERROR", "purchasePrice must be positive");
    }

    /**
     * Проверяет поля правила и порядок ценовых границ. Если верхняя граница задана, нижняя должна быть строго
     * меньше неё; нарушение вызывает {@code VALIDATION_ERROR} до записи в БД.
     *
     * @param request полный набор условий тарифного правила
     */
    private void validateRule(RuleRequest request) {
        validate(request);
        if (request.upperBound() != null && new BigDecimal(request.lowerBound()).compareTo(new BigDecimal(request.upperBound())) >= 0)
            throw new TariffApiException(400, "VALIDATION_ERROR", "lowerBound must be less than upperBound");
    }

    /**
     * Проверяет ограничения, объявленные на полях запроса. Отсутствующий запрос или нарушения вызывают {@code
     * VALIDATION_ERROR}; список нарушений сортирует по имени поля и сообщению, не включая отклонённые
     * значения.
     *
     * @param request модель запроса для проверки объявленных на её полях ограничений
     */
    private void validate(Object request) {
        if (request == null) throw new TariffApiException(400, "VALIDATION_ERROR", "Request is required");
        List<ErrorDetail> details = validator.validate(request).stream()
                .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message)).toList();
        if (!details.isEmpty()) throw new TariffApiException(400, "VALIDATION_ERROR", "Invalid tariff request", details);
    }

    /**
     * Создаёт ошибку HTTP 404 с кодом {@code NOT_FOUND} для отсутствующего правила.
     *
     * @return исключение тарифов с подготовленными статусом, кодом и пояснением
     */
    private TariffApiException notFound() { return new TariffApiException(404, "NOT_FOUND", "Tariff rule not found"); }
}
