package com.tariffs.pyramid.http;

import com.fasterxml.jackson.databind.*;
import com.tariffs.api.TariffApiException;
import com.tariffs.api.TariffModels.*;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import java.util.UUID;

/**
 * Учебный CRUD-клиент, существующий только в src/test: production WAREHOUSE не выполняет CRUD тарифов.
 * Позволяет повторить девять сценариев на границе HTTP с WireMock. Не содержит бизнес-логики сервиса или хранилища.
 */
public final class TariffRulesHttpClient {
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Создаёт клиент отдельного HTTP-стенда; не запускает Spring-контекст или приложение.
     * @param baseUrl адрес WireMock, назначенный текущему тесту
     */
    public TariffRulesHttpClient(String baseUrl) { http = RestClient.builder().baseUrl(baseUrl).build(); }

    /** Передаёт все исходные поля POST и читает ответ версии 1; проверяет HTTP 201, а не факт записи в БД. */
    public Rule create(RuleRequest request) { return exchange(HttpMethod.POST, "/tariffs/rules", request, 201, Rule.class); }

    /** Читает правило GET по UUID; ошибка HTTP преобразуется в тот же статус и код, что на остальных уровнях. */
    public Rule get(UUID id) { return exchange(HttpMethod.GET, "/tariffs/rules/" + id, null, 200, Rule.class); }

    /** Отправляет полную замену PUT, включая upperBound=null; ожидает HTTP 200 и полную модель ответа. */
    public Rule update(UUID id, RuleRequest request) { return exchange(HttpMethod.PUT, "/tariffs/rules/" + id, request, 200, Rule.class); }

    /** Отправляет DELETE конкретного UUID; успешный ответ должен быть HTTP 204 без тела. */
    public void delete(UUID id) { exchange(HttpMethod.DELETE, "/tariffs/rules/" + id, null, 204, Void.class); }

    /** Читает публичный список для сравнения состояния до и после ошибочного создания. */
    public RulesResponse list() { return exchange(HttpMethod.GET, "/tariffs/rules", null, 200, RulesResponse.class); }

    /**
     * Выполняет один HTTP-запрос, читает исходное тело и проверяет ожидаемый статус.
     * @param method HTTP-метод
     * @param path путь API
     * @param body исходные поля либо null, если тело не нужно
     * @param expected ожидаемый успешный HTTP-статус
     * @param type тип модели ответа
     * @return разобранный ответ или null для DELETE
     * @throws TariffApiException если сервер вернул ошибку; статус и машинный код сохраняются
     */
    private <T> T exchange(HttpMethod method, String path, RuleRequest body, int expected, Class<T> type) {
        var request = http.method(method).uri(path);
        if (body != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, received) -> {
            int status = received.getStatusCode().value();
            if (status != expected) {
                JsonNode error = mapper.readTree(received.getBody());
                throw new TariffApiException(status, error.path("code").asText(), error.path("message").asText());
            }
            if (type == Void.class) return null;
            return mapper.readValue(received.getBody(), type);
        });
    }
}
