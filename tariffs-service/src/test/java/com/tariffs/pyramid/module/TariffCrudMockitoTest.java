package com.tariffs.pyramid.module;

import com.tariffs.dto.RuleRequest;
import com.tariffs.exception.TariffApiException;
import com.tariffs.model.Rule;

import com.tariffs.repository.TariffRuleRepository;
import com.tariffs.service.TariffRuleService;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Проверяет создание, чтение, замену и удаление правил в коде {@code TariffRuleService}. Репозиторий
 * заменён Mockito-объектом с заранее заданными ответами; SQL и Spring не запускаются.
 */
@Tag("module") @ExtendWith(MockitoExtension.class)
class TariffCrudMockitoTest {
    @Mock private TariffRuleRepository repository;
    private ValidatorFactory validation;
    private TariffRuleService service;
    private final UUID id = UUID.fromString("10000000-0000-4000-8000-000000000001");

    /**
     * Создаёт проверку ограничений полей и сервис с подставным репозиторием. Каждый тест задаёт собственные
     * ответы и ожидаемые обращения Mockito.
     */
    @BeforeEach void setup() {
        validation = Validation.buildDefaultValidatorFactory();
        service = new TariffRuleService(repository, validation.getValidator());
    }

    /**
     * Закрывает фабрику проверки полей после теста, освобождая её ресурсы.
     */
    @AfterEach void cleanup() { validation.close(); }

    /**
     * TAR-CRUD-001-MOCK. Репозиторий допускает создание. Передаёт тип {@code NON_FOOD}, валюту {@code RUB},
     * границы 0.00–500.00 и наценку 0.20. Проверяет все поля, версию 1 и передачу исходных условий и нового
     * UUID в запись. Ответ чтения задан тестом; сохранение SQL здесь не проверяется.
     */
    @Test @DisplayName("TAR-CRUD-001-MOCK: создание правила версии 1")
    void createsRule() {
        when(repository.find(any(UUID.class))).thenAnswer(call -> Optional.of(rule(call.getArgument(0), 1, input())));
        Rule result = service.create(input());
        assertRule(result, 1, input());
        verify(repository).lockCatalog();
        verify(repository).count();
        verify(repository).insert(result.tariffRuleId(), input());
    }

    /**
     * TAR-CRUD-002-MOCK. Репозиторий возвращает подготовленное правило версии 1. Читает его по UUID и
     * проверяет все поля и обращение к нужному идентификатору.
     */
    @Test @DisplayName("TAR-CRUD-002-MOCK: чтение созданного правила")
    void readsRule() {
        Rule existing = rule(id, 1, input());
        when(repository.find(id)).thenReturn(Optional.of(existing));
        assertThat(service.get(id)).isEqualTo(existing);
        verify(repository).find(id);
    }

    /**
     * TAR-CRUD-003-MOCK. Заменяет правило версии 1: тип {@code FOOD}, нижняя граница 5.00, верхнего предела
     * нет, наценка 0.30. Проверяет прежний UUID, версию 2, новые поля, блокировку и передачу полной замены
     * репозиторию. Версию 2 возвращает Mockito; увеличение версии в SQL здесь не выполняется.
     */
    @Test @DisplayName("TAR-CRUD-003-MOCK: полная замена и версия 2")
    void replacesRule() {
        when(repository.lock(id)).thenReturn(Optional.of(rule(id, 1, input())));
        when(repository.find(id)).thenReturn(Optional.of(rule(id, 2, replacement())));
        Rule result = service.update(id, replacement());
        assertThat(result.tariffRuleId()).isEqualTo(id);
        assertRule(result, 2, replacement());
        verify(repository).lock(id);
        verify(repository).replace(id, replacement());
    }

    /**
     * TAR-CRUD-004-MOCK. Репозиторий подтверждает удаление существующего правила, затем сообщает об отсутствии
     * записи. Удаляет правило и проверяет {@code NOT_FOUND} и HTTP 404 при последующем чтении; физическое
     * удаление в БД не выполняется.
     */
    @Test @DisplayName("TAR-CRUD-004-MOCK: удаление и отсутствие при чтении")
    void deletesRule() {
        when(repository.delete(id)).thenReturn(true);
        service.delete(id);
        assertError(() -> service.get(id), 404, "NOT_FOUND");
        verify(repository).delete(id);
    }

    /**
     * TAR-CRUD-005-MOCK. Репозиторий сообщает об отсутствии UUID. Чтение должно вызвать {@code NOT_FOUND} и
     * HTTP 404.
     */
    @Test @DisplayName("TAR-CRUD-005-MOCK: чтение неизвестного UUID")
    void rejectsMissingRead() { assertError(() -> service.get(id), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-006-MOCK. Для отсутствующего UUID передаёт допустимые новые условия. Проверяет {@code
     * NOT_FOUND} и HTTP 404, а также отсутствие вызова записи замены: новая строка не должна создаваться.
     */
    @Test @DisplayName("TAR-CRUD-006-MOCK: замена неизвестного UUID")
    void rejectsMissingUpdate() {
        assertError(() -> service.update(id, replacement()), 404, "NOT_FOUND");
        verify(repository, never()).replace(any(), any());
    }

    /**
     * TAR-CRUD-007-MOCK. Репозиторий возвращает {@code false} при удалении неизвестного UUID. Проверяет {@code
     * NOT_FOUND} и HTTP 404.
     */
    @Test @DisplayName("TAR-CRUD-007-MOCK: удаление неизвестного UUID")
    void rejectsMissingDelete() { assertError(() -> service.delete(id), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-008-MOCK. Передаёт создание с одинаковыми границами 100.00. Проверяет {@code VALIDATION_ERROR}
     * и HTTP 400; репозиторий вообще не должен вызываться.
     */
    @Test @DisplayName("TAR-CRUD-008-MOCK: неверное создание ничего не сохраняет")
    void rejectsInvalidCreate() {
        assertError(() -> service.create(invalid()), 400, "VALIDATION_ERROR");
        verifyNoInteractions(repository);
    }

    /**
     * TAR-CRUD-009-MOCK. Пытается заменить правило версии 1 условиями с равными границами. Проверяет {@code
     * VALIDATION_ERROR} и HTTP 400, отсутствие записи замены и прежние поля и версию при чтении.
     */
    @Test @DisplayName("TAR-CRUD-009-MOCK: неверная замена сохраняет исходное правило")
    void rejectsInvalidUpdate() {
        Rule existing = rule(id, 1, input());
        when(repository.find(id)).thenReturn(Optional.of(existing));
        assertError(() -> service.update(id, invalid()), 400, "VALIDATION_ERROR");
        assertThat(service.get(id)).isEqualTo(existing);
        verify(repository, never()).replace(any(), any());
    }

    /**
     * Возвращает исходные условия правила для теста. Цены и наценка заданы строками независимо от проверяемого
     * расчёта.
     *
     * @return допустимые исходные условия правила
     */
    private RuleRequest input() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "0.00", "500.00", "0.20"); }
    /**
     * Возвращает новые условия: другой тип товара и отсутствие верхней границы. Эти значения позволяют
     * обнаружить неполную замену полей.
     *
     * @return новые условия полной замены правила
     */
    private RuleRequest replacement() { return new RuleRequest("FOOD", "PYRAMID", "RUB", "5.00", null, "0.30"); }
    /**
     * Возвращает неверные условия: нижняя и верхняя границы цены равны 100.00.
     *
     * @return неверные условия с равными границами цены
     */
    private RuleRequest invalid() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "100.00", "100.00", "0.20"); }
    /**
     * Создаёт заранее заданный ответ репозитория из условий, UUID и версии. Это ответ подставной зависимости,
     * а не сохранение записи.
     *
     * @param uuid UUID тарифного правила
     * @param version версия тарифного правила
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    private Rule rule(UUID uuid, long version, RuleRequest request) {
        return new Rule(uuid, version, request.productType(), request.cityId(), request.currency(),
                request.lowerBound(), request.upperBound(), request.markupRate());
    }
    /**
     * Сравнивает UUID, версию и каждое поле правила с заданными ожиданиями, чтобы обнаружить неполную замену.
     *
     * @param result фактически полученное правило для сравнения с ожиданием
     * @param version версия тарифного правила
     * @param expected ожидаемые условия правила
     */
    private void assertRule(Rule result, long version, RuleRequest expected) {
        assertThat(result.tariffRuleId()).isNotNull();
        assertThat(result).isEqualTo(rule(result.tariffRuleId(), version, expected));
    }
    /**
     * Вызывает операцию, которая должна завершиться ошибкой, и сравнивает HTTP-статус и код исключения с
     * ожидаемыми.
     *
     * @param action операция, которая должна вызвать проверяемое исключение
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     */
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TariffApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }
}
