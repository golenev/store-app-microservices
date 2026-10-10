package com.tariffs.pyramid.http;

import com.tariffs.dto.RuleRequest;
import com.tariffs.dto.RulesResponse;
import com.tariffs.exception.TariffApiException;
import com.tariffs.model.Rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

/**
 * Проверяет девять сценариев HTTP-клиента тарифных правил. WireMock принимает сетевые запросы и возвращает
 * заданные тестом ответы; приложение TARIFFS и PostgreSQL не запускаются. Проверяются запросы, чтение
 * ответов и обработка ошибок клиента, а не сохранение правил в БД.
 */
@Tag("http")
class TariffCrudWireMockTest {
    private WireMockServer server;
    private TariffRulesHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.fromString("10000000-0000-4000-8000-000000000001");

    /**
     * Запускает отдельный WireMock на свободном порту и создаёт HTTP-клиент для текущего теста.
     */
    @BeforeEach void setup() {
        server = new WireMockServer(wireMockConfig().dynamicPort()); server.start();
        client = new TariffRulesHttpClient(server.baseUrl());
    }

    /**
     * Останавливает WireMock после теста. Его настроенные ответы и журнал запросов не переносятся в следующий
     * сценарий.
     */
    @AfterEach void cleanup() { server.stop(); }

    /**
     * TAR-CRUD-001-HTTP. Отправляет POST: {@code NON_FOOD}, {@code RUB}, границы 0.00–500.00, наценка 0.20.
     * WireMock отвечает HTTP 201 с UUID и версией 1. Проверяет все поля ответа и точный JSON запроса;
     * сохранение записи сервер только имитирует.
     */
    @Test @DisplayName("TAR-CRUD-001-HTTP: создание правила версии 1")
    void createsRule() throws Exception {
        creation();
        Rule result = client.create(input());
        assertRule(result, 1, input());
        server.verify(1, postRequestedFor(urlEqualTo("/tariffs/rules")).withRequestBody(equalToJson(json(input()))));
    }

    /**
     * TAR-CRUD-002-HTTP. WireMock отвечает на создание правилом версии 1 и возвращает его при GET по UUID.
     * Проверяет все поля и путь запроса; БД не участвует.
     */
    @Test @DisplayName("TAR-CRUD-002-HTTP: чтение созданного правила")
    void readsRule() throws Exception {
        creation(); reply("GET", path(), 200, rule(1, input()));
        Rule existing = client.create(input());
        assertThat(client.get(existing.tariffRuleId())).isEqualTo(existing);
        server.verify(1, getRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-003-HTTP. Отправляет замену: {@code FOOD}, нижняя граница 5.00, {@code upperBound = null},
     * наценка 0.30. WireMock отвечает прежним UUID и версией 2 на PUT и GET. Проверяет новые поля и явное
     * присутствие {@code null} в JSON запроса.
     */
    @Test @DisplayName("TAR-CRUD-003-HTTP: полная замена и версия 2")
    void replacesRule() throws Exception {
        creation(); reply("PUT", path(), 200, rule(2, replacement())); reply("GET", path(), 200, rule(2, replacement()));
        Rule previous = client.create(input());
        Rule result = client.update(previous.tariffRuleId(), replacement());
        assertThat(result.tariffRuleId()).isEqualTo(previous.tariffRuleId());
        assertRule(result, 2, replacement()); assertThat(client.get(id)).isEqualTo(result);
        server.verify(1, putRequestedFor(urlEqualTo(path())).withRequestBody(equalToJson(json(replacement()))));
    }

    /**
     * TAR-CRUD-004-HTTP. Для созданного правила WireMock отвечает HTTP 204 на DELETE и {@code NOT_FOUND} с
     * HTTP 404 на GET. Проверяет запрос удаления нужного UUID и ошибку чтения; SQL не выполняется.
     */
    @Test @DisplayName("TAR-CRUD-004-HTTP: удаление и отсутствие при чтении")
    void deletesRule() throws Exception {
        creation(); reply("DELETE", path(), 204, null); error("GET", 404, "NOT_FOUND");
        client.create(input()); client.delete(id);
        assertError(() -> client.get(id), 404, "NOT_FOUND");
        server.verify(1, deleteRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-005-HTTP. WireMock сообщает об отсутствующем UUID при GET. Проверяет, что клиент сохраняет код
     * {@code NOT_FOUND} и статус HTTP 404 в исключении.
     */
    @Test @DisplayName("TAR-CRUD-005-HTTP: чтение неизвестного UUID")
    void rejectsMissingRead() throws Exception {
        error("GET", 404, "NOT_FOUND"); assertError(() -> client.get(id), 404, "NOT_FOUND");
        server.verify(1, getRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-006-HTTP. Для отсутствующего UUID WireMock отвечает {@code NOT_FOUND} и HTTP 404 на PUT и GET.
     * Проверяет полный запрос замены и сохранение ошибки вместо успешного результата.
     */
    @Test @DisplayName("TAR-CRUD-006-HTTP: замена неизвестного UUID")
    void rejectsMissingUpdate() throws Exception {
        error("PUT", 404, "NOT_FOUND"); error("GET", 404, "NOT_FOUND");
        assertError(() -> client.update(id, replacement()), 404, "NOT_FOUND");
        assertError(() -> client.get(id), 404, "NOT_FOUND");
        server.verify(1, putRequestedFor(urlEqualTo(path())).withRequestBody(equalToJson(json(replacement()))));
    }

    /**
     * TAR-CRUD-007-HTTP. WireMock отвечает {@code NOT_FOUND} и HTTP 404 на удаление отсутствующего UUID.
     * Проверяет статус и код исключения клиента.
     */
    @Test @DisplayName("TAR-CRUD-007-HTTP: удаление неизвестного UUID")
    void rejectsMissingDelete() throws Exception {
        error("DELETE", 404, "NOT_FOUND"); assertError(() -> client.delete(id), 404, "NOT_FOUND");
        server.verify(1, deleteRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-008-HTTP. Отправляет POST с равными границами 100.00 и получает {@code VALIDATION_ERROR} с HTTP
     * 400. Проверяет тело запроса и одинаковый список до и после него. Неизменность списка задаёт WireMock; БД
     * не проверяется.
     */
    @Test @DisplayName("TAR-CRUD-008-HTTP: неверное создание ничего не сохраняет")
    void rejectsInvalidCreate() throws Exception {
        reply("GET", "/tariffs/rules", 200, new RulesResponse(List.of()));
        reply("POST", "/tariffs/rules", 400, Map.of("code", "VALIDATION_ERROR", "message", "Invalid bounds"));
        RulesResponse before = client.list();
        assertError(() -> client.create(invalid()), 400, "VALIDATION_ERROR");
        assertThat(client.list()).isEqualTo(before);
        server.verify(1, postRequestedFor(urlEqualTo("/tariffs/rules")).withRequestBody(equalToJson(json(invalid()))));
    }

    /**
     * TAR-CRUD-009-HTTP. Для созданного правила версии 1 отправляет PUT с равными границами 100.00. Проверяет
     * {@code VALIDATION_ERROR} и HTTP 400, затем прежние поля и версию из ответа GET. Сохранение прежнего
     * состояния имитирует WireMock.
     */
    @Test @DisplayName("TAR-CRUD-009-HTTP: неверная замена сохраняет исходное правило")
    void rejectsInvalidUpdate() throws Exception {
        creation(); error("PUT", 400, "VALIDATION_ERROR"); reply("GET", path(), 200, rule(1, input()));
        Rule existing = client.create(input());
        assertError(() -> client.update(id, invalid()), 400, "VALIDATION_ERROR");
        assertThat(client.get(id)).isEqualTo(existing);
        server.verify(1, putRequestedFor(urlEqualTo(path())).withRequestBody(equalToJson(json(invalid()))));
    }

    /**
     * Настраивает ответ POST для подготовки правила версии 1. Ответ задан явно; тестовое хранилище здесь не
     * реализуется.
     */
    private void creation() throws Exception { reply("POST", "/tariffs/rules", 201, rule(1, input())); }
    /**
     * Возвращает адрес API правила по указанному UUID.
     *
     * @return адрес API выбранного тарифного правила
     */
    private String path() { return "/tariffs/rules/" + id; }
    /**
     * Возвращает исходные условия правила, совпадающие с условиями сценариев Mockito и PostgreSQL.
     *
     * @return допустимые исходные условия правила
     */
    private RuleRequest input() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "0.00", "500.00", "0.20"); }
    /**
     * Возвращает полный набор новых условий. Верхняя граница явно передаётся в JSON как {@code upperBound =
     * null}.
     *
     * @return новые условия полной замены правила
     */
    private RuleRequest replacement() { return new RuleRequest("FOOD", "PYRAMID", "RUB", "5.00", null, "0.30"); }
    /**
     * Возвращает неверный диапазон цены с двумя границами 100.00.
     *
     * @return неверные условия с равными границами цены
     */
    private RuleRequest invalid() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "100.00", "100.00", "0.20"); }
    /**
     * Создаёт заранее заданную модель ответа WireMock. UUID и версия назначаются тестом, а не вычисляются
     * клиентом.
     *
     * @param version версия тарифного правила
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    private Rule rule(long version, RuleRequest request) {
        return new Rule(id, version, request.productType(), request.cityId(), request.currency(),
                request.lowerBound(), request.upperBound(), request.markupRate());
    }
    /**
     * Сравнивает все поля и версию ответа с заданными ожиданиями, чтобы обнаружить неполное или неверное
     * чтение JSON.
     *
     * @param result фактически полученное правило для сравнения с ожиданием
     * @param version версия тарифного правила
     * @param expected ожидаемые условия правила
     */
    private void assertRule(Rule result, long version, RuleRequest expected) { assertThat(result).isEqualTo(rule(version, expected)); }
    /**
     * Выполняет запрос с заданной HTTP-ошибкой и сравнивает статус и код исключения клиента с ожидаемыми.
     *
     * @param action операция, которая должна вызвать проверяемое исключение
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     */
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TariffApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status); assertThat(error.code()).isEqualTo(code);
        });
    }
    /**
     * Преобразует заданные поля в JSON для ответа или сравнения запроса. Цены остаются строками, поля с {@code
     * null} сохраняются.
     *
     * @param value Java-объект для преобразования в JSON
     * @return JSON-запись переданного объекта
     */
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    /**
     * Настраивает один ответ WireMock для выбранных метода и пути. Тело и статус задаются тестом; состояние
     * приложения не вычисляется.
     *
     * @param method HTTP-метод запроса
     * @param path путь HTTP-запроса
     * @param status HTTP-статус ответа
     * @param body явно заданные поля ответа или {@code null} для HTTP 204
     */
    private void reply(String method, String path, int status, Object body) throws Exception {
        var response = aResponse().withStatus(status).withHeader("Content-Type", "application/json");
        if (body != null) response.withBody(json(body));
        server.stubFor(request(method, urlEqualTo(path)).willReturn(response));
    }
    /**
     * Настраивает ответ об ошибке для выбранной операции и UUID. Сценарий отдельно проверяет, как клиент
     * прочитал код и HTTP-статус.
     *
     * @param method HTTP-метод запроса
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     */
    private void error(String method, int status, String code) throws Exception {
        reply(method, path(), status, Map.of("code", code, "message", "Expected scenario failure"));
    }
}
