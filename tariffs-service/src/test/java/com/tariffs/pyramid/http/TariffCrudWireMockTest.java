package com.tariffs.pyramid.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.tariffs.api.*;
import com.tariffs.api.TariffModels.*;
import com.tariffs.pyramid.http.TariffRulesHttpClient;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

/**
 * Девять общих CRUD-сценариев для учебного HTTP-клиента и WireMock.
 * Клиент работает своим кодом, внешний сервер возвращает явно заданные ответы. TARIFFS и SQL не запускаются.
 * Этот набор показывает HTTP-интеграцию; он не подтверждает выполнение production CRUD внутри TARIFFS.
 */
@Tag("http")
class TariffCrudWireMockTest {
    private WireMockServer server;
    private TariffRulesHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.fromString("10000000-0000-4000-8000-000000000001");

    /** Создаёт независимый WireMock на свободном порту и настоящий учебный HTTP-клиент для текущего сценария. */
    @BeforeEach void setup() {
        server = new WireMockServer(wireMockConfig().dynamicPort()); server.start();
        client = new TariffRulesHttpClient(server.baseUrl());
    }

    /** Останавливает имитационный HTTP-сервер; ответы и журнал запросов не переносятся в следующий тест. */
    @AfterEach void cleanup() { server.stop(); }

    /**
     * TAR-CRUD-001-HTTP. POST передаёт NON_FOOD/RUB, границы 0.00–500.00 и ставку 0.20.
     * WireMock отвечает 201 с UUID/version=1. Проверяем все поля ответа и точное JSON-тело исходящего запроса.
     * Сохранение только имитируется ответом сервера; реальная запись отдельно проверяется на DB/API уровнях.
     */
    @Test @DisplayName("TAR-CRUD-001-HTTP: создание правила версии 1")
    void createsRule() throws Exception {
        creation();
        Rule result = client.create(input());
        assertRule(result, 1, input());
        server.verify(1, postRequestedFor(urlEqualTo("/tariffs/rules")).withRequestBody(equalToJson(json(input()))));
    }

    /**
     * TAR-CRUD-002-HTTP. Подготовка POST возвращает правило версии 1, GET по UUID возвращает ту же запись.
     * Проверяем полное равенство и путь запроса. WireMock задаёт состояние; движок БД не участвует.
     */
    @Test @DisplayName("TAR-CRUD-002-HTTP: чтение созданного правила")
    void readsRule() throws Exception {
        creation(); reply("GET", path(), 200, rule(1, input()));
        Rule existing = client.create(input());
        assertThat(client.get(existing.tariffRuleId())).isEqualTo(existing);
        server.verify(1, getRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-003-HTTP. Созданную версию 1 заменяем: FOOD, граница 5.00, upperBound=null, ставка 0.30.
     * PUT/GET возвращают прежний UUID и version=2. Проверяем все поля и наличие явного null в JSON запроса.
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
     * TAR-CRUD-004-HTTP. После подготовки существующей записи DELETE отвечает 204, затем GET — NOT_FOUND/404.
     * Проверяем отсутствие результата чтения и запрос удаления нужного UUID; физического DELETE SQL здесь нет.
     */
    @Test @DisplayName("TAR-CRUD-004-HTTP: удаление и отсутствие при чтении")
    void deletesRule() throws Exception {
        creation(); reply("DELETE", path(), 204, null); error("GET", 404, "NOT_FOUND");
        client.create(input()); client.delete(id);
        assertError(() -> client.get(id), 404, "NOT_FOUND");
        server.verify(1, deleteRequestedFor(urlEqualTo(path())));
    }

    /** TAR-CRUD-005-HTTP. Сервер сообщает об отсутствующем UUID; GET преобразуется в NOT_FOUND/404. */
    @Test @DisplayName("TAR-CRUD-005-HTTP: чтение неизвестного UUID")
    void rejectsMissingRead() throws Exception {
        error("GET", 404, "NOT_FOUND"); assertError(() -> client.get(id), 404, "NOT_FOUND");
        server.verify(1, getRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-006-HTTP. Допустимая замена отсутствующего UUID получает NOT_FOUND/404; GET также сообщает отсутствие.
     * Проверяем правильный PUT и полную передачу новых полей; клиент не маскирует отказ успешным результатом.
     */
    @Test @DisplayName("TAR-CRUD-006-HTTP: замена неизвестного UUID")
    void rejectsMissingUpdate() throws Exception {
        error("PUT", 404, "NOT_FOUND"); error("GET", 404, "NOT_FOUND");
        assertError(() -> client.update(id, replacement()), 404, "NOT_FOUND");
        assertError(() -> client.get(id), 404, "NOT_FOUND");
        server.verify(1, putRequestedFor(urlEqualTo(path())).withRequestBody(equalToJson(json(replacement()))));
    }

    /** TAR-CRUD-007-HTTP. DELETE отсутствующего UUID возвращает NOT_FOUND/404; проверяется сохранение кода и статуса. */
    @Test @DisplayName("TAR-CRUD-007-HTTP: удаление неизвестного UUID")
    void rejectsMissingDelete() throws Exception {
        error("DELETE", 404, "NOT_FOUND"); assertError(() -> client.delete(id), 404, "NOT_FOUND");
        server.verify(1, deleteRequestedFor(urlEqualTo(path())));
    }

    /**
     * TAR-CRUD-008-HTTP. POST с равными границами 100.00 получает VALIDATION_ERROR/400.
     * Публичный список до и после одинаков; проверяем тело ошибочного запроса. Неизменность списка задана WireMock.
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
     * TAR-CRUD-009-HTTP. Для созданной версии 1 отправляем PUT с равными границами 100.00.
     * Получаем VALIDATION_ERROR/400; GET возвращает исходные поля/version. WireMock имитирует сохранённое состояние.
     */
    @Test @DisplayName("TAR-CRUD-009-HTTP: неверная замена сохраняет исходное правило")
    void rejectsInvalidUpdate() throws Exception {
        creation(); error("PUT", 400, "VALIDATION_ERROR"); reply("GET", path(), 200, rule(1, input()));
        Rule existing = client.create(input());
        assertError(() -> client.update(id, invalid()), 400, "VALIDATION_ERROR");
        assertThat(client.get(id)).isEqualTo(existing);
        server.verify(1, putRequestedFor(urlEqualTo(path())).withRequestBody(equalToJson(json(invalid()))));
    }

    /** Задаёт только ответ POST для подготовки записи версии 1; не реализует тестовую базу данных или CRUD-движок. */
    private void creation() throws Exception { reply("POST", "/tariffs/rules", 201, rule(1, input())); }
    /** Возвращает путь выбранного UUID для проверки обращения к нужному ресурсу. */
    private String path() { return "/tariffs/rules/" + id; }
    /** Возвращает одинаковые исходные поля для HTTP, Mockito и PostgreSQL-наборов. */
    private RuleRequest input() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "0.00", "500.00", "0.20"); }
    /** Возвращает полную замену; null передаётся в JSON как явное поле upperBound. */
    private RuleRequest replacement() { return new RuleRequest("FOOD", "PYRAMID", "RUB", "5.00", null, "0.30"); }
    /** Возвращает пустой диапазон с двумя одинаковыми границами 100.00. */
    private RuleRequest invalid() { return new RuleRequest("NON_FOOD", "PYRAMID", "RUB", "100.00", "100.00", "0.20"); }
    /** Создаёт явно заданную модель ответа сервера; UUID и версия не вычисляются HTTP-клиентом. */
    private Rule rule(long version, RuleRequest request) {
        return new Rule(id, version, request.productType(), request.cityId(), request.currency(),
                request.lowerBound(), request.upperBound(), request.markupRate());
    }
    /** Сверяет каждое поле ответа и версию с ожиданием; частичный ответ или неверная десериализация обнаруживаются. */
    private void assertRule(Rule result, long version, RuleRequest expected) { assertThat(result).isEqualTo(rule(version, expected)); }
    /** Выполняет операцию с ошибочным HTTP-ответом и проверяет сохранение статуса и кода в исключении клиента. */
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TariffApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status); assertThat(error.code()).isEqualTo(code);
        });
    }
    /** Сериализует явно заданные поля для ответа/проверки запроса; деньги остаются строками, null не удаляется. */
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    /**
     * Задаёт один HTTP-ответ выбранного метода и пути; состояние приложения не рассчитывает.
     * @param method HTTP-метод
     * @param path точный путь
     * @param status статус ответа
     * @param body явно заданные поля ответа или null для HTTP 204
     */
    private void reply(String method, String path, int status, Object body) throws Exception {
        var response = aResponse().withStatus(status).withHeader("Content-Type", "application/json");
        if (body != null) response.withBody(json(body));
        server.stubFor(request(method, urlEqualTo(path)).willReturn(response));
    }
    /** Задаёт стандартную ошибку выбранной операции по UUID; код и статус проверяются отдельно каждым сценарием. */
    private void error(String method, int status, String code) throws Exception {
        reply(method, path(), status, Map.of("code", code, "message", "Expected scenario failure"));
    }
}
