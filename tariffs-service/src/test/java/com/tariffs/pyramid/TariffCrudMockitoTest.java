package com.tariffs.pyramid;

import com.tariffs.api.*;
import com.tariffs.api.TariffModels.*;
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

/** CRUD настоящего TariffRuleService с ответами репозитория, заданными Mockito. SQL и Spring не запускаются. */
@Tag("module") @ExtendWith(MockitoExtension.class)
class TariffCrudMockitoTest {
    @Mock private TariffRuleRepository repository;
    private ValidatorFactory validation;
    private TariffRuleService service;
    private final UUID id = UUID.fromString("10000000-0000-4000-8000-000000000001");

    /** Создаёт реальную Bean Validation и сервис с мок-репозиторием; каждый тест получает независимые ожидания Mockito. */
    @BeforeEach void setup() {
        validation = Validation.buildDefaultValidatorFactory();
        service = new TariffRuleService(repository, validation.getValidator());
    }

    /** Закрывает фабрику валидации после сценария, не сохраняя её ресурсы между тестами. */
    @AfterEach void cleanup() { validation.close(); }

    /**
     * TAR-CRUD-001-MOCK. Каталог позволяет создание. Создаём NON_FOOD/RUB, границы 0.00–500.00, ставку 0.20.
     * Проверяем все поля и version=1, а также передачу исходных полей и созданного UUID в insert.
     * Ответ find задан тестом; эта проверка не доказывает, что SQL сохранит запись.
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
     * TAR-CRUD-002-MOCK. Подготовлено правило версии 1 с исходными полями.
     * Читаем его по UUID и проверяем полное равенство. Mockito подтверждает выбор UUID, но не чтение PostgreSQL.
     */
    @Test @DisplayName("TAR-CRUD-002-MOCK: чтение созданного правила")
    void readsRule() {
        Rule existing = rule(id, 1, input());
        when(repository.find(id)).thenReturn(Optional.of(existing));
        assertThat(service.get(id)).isEqualTo(existing);
        verify(repository).find(id);
    }

    /**
     * TAR-CRUD-003-MOCK. Правило версии 1 заменяем: FOOD, границы 5.00–без верхнего предела, ставка 0.30.
     * UUID сохраняется, возвращается version=2 и все новые поля. Проверяем блокировку и полный replace.
     * Приращение версии в SQL здесь не выполняется: версию 2 возвращает мок.
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
     * TAR-CRUD-004-MOCK. Существующее правило успешно удаляется; последующее чтение сообщает NOT_FOUND/404.
     * Репозиторий задан как вернувший успешное удаление и отсутствие строки, SQL не выполняется.
     */
    @Test @DisplayName("TAR-CRUD-004-MOCK: удаление и отсутствие при чтении")
    void deletesRule() {
        when(repository.delete(id)).thenReturn(true);
        service.delete(id);
        assertError(() -> service.get(id), 404, "NOT_FOUND");
        verify(repository).delete(id);
    }

    /**
     * TAR-CRUD-005-MOCK. Неизвестный UUID отсутствует в репозитории.
     * Чтение возвращает NOT_FOUND/404, а не пустое успешное правило.
     */
    @Test @DisplayName("TAR-CRUD-005-MOCK: чтение неизвестного UUID")
    void rejectsMissingRead() { assertError(() -> service.get(id), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-006-MOCK. UUID отсутствует; отправляем допустимую полную замену.
     * Проверяем NOT_FOUND/404 и отсутствие replace: update не должен создавать новую запись.
     */
    @Test @DisplayName("TAR-CRUD-006-MOCK: замена неизвестного UUID")
    void rejectsMissingUpdate() {
        assertError(() -> service.update(id, replacement()), 404, "NOT_FOUND");
        verify(repository, never()).replace(any(), any());
    }

    /**
     * TAR-CRUD-007-MOCK. UUID отсутствует; удаление возвращает false.
     * Проверяем NOT_FOUND/404 вместо сообщения об успешном удалении.
     */
    @Test @DisplayName("TAR-CRUD-007-MOCK: удаление неизвестного UUID")
    void rejectsMissingDelete() { assertError(() -> service.delete(id), 404, "NOT_FOUND"); }

    /**
     * TAR-CRUD-008-MOCK. Создаём правило с одинаковыми границами 100.00 и 100.00.
     * Проверяем VALIDATION_ERROR/400 и отсутствие любых обращений к репозиторию: пустой диапазон недопустим.
     */
    @Test @DisplayName("TAR-CRUD-008-MOCK: неверное создание ничего не сохраняет")
    void rejectsInvalidCreate() {
        assertError(() -> service.create(invalid()), 400, "VALIDATION_ERROR");
        verifyNoInteractions(repository);
    }

    /**
     * TAR-CRUD-009-MOCK. Существует правило версии 1. Пробуем заменить его правилом с равными границами.
     * Проверяем VALIDATION_ERROR/400 и исходные поля/version при чтении; replace не вызывается.
     */
    @Test @DisplayName("TAR-CRUD-009-MOCK: неверная замена сохраняет исходное правило")
    void rejectsInvalidUpdate() {
        Rule existing = rule(id, 1, input());
        when(repository.find(id)).thenReturn(Optional.of(existing));
        assertError(() -> service.update(id, invalid()), 400, "VALIDATION_ERROR");
        assertThat(service.get(id)).isEqualTo(existing);
        verify(repository, never()).replace(any(), any());
    }

    /** Возвращает независимые исходные поля учебного правила; деньги и ставка представлены строками. */
    private RuleRequest input() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "0.00", "500.00", "0.20"); }
    /** Возвращает полный набор новых полей; null верхнего предела должен сохраниться, а FOOD отличаться от NON_FOOD. */
    private RuleRequest replacement() { return new RuleRequest("FOOD", "PYRAMID", "RUB", "5.00", null, "0.30"); }
    /** Возвращает пустой диапазон: lowerBound и upperBound равны 100.00. */
    private RuleRequest invalid() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "100.00", "100.00", "0.20"); }
    /** Строит ответ мока из явно заданных полей и версии; это подготовка зависимости, а не реализация CRUD-хранилища. */
    private Rule rule(UUID uuid, long version, RuleRequest request) {
        return new Rule(uuid, version, request.productType(), request.cityId(), request.currency(),
                request.lowerBound(), request.upperBound(), request.markupRate());
    }
    /** Проверяет UUID, версию и каждое бизнес-поле независимо, чтобы частичная замена не осталась незамеченной. */
    private void assertRule(Rule result, long version, RuleRequest expected) {
        assertThat(result.tariffRuleId()).isNotNull();
        assertThat(result).isEqualTo(rule(result.tariffRuleId(), version, expected));
    }
    /** Выполняет ошибочную операцию и сверяет одновременно HTTP-смысл статуса и машинный код ошибки сервиса. */
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TariffApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }
}
