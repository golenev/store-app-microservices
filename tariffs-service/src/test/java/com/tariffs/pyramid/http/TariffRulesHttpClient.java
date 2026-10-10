package com.tariffs.pyramid.http;

import com.tariffs.dto.RuleRequest;
import com.tariffs.dto.RulesResponse;
import com.tariffs.exception.TariffApiException;
import com.tariffs.model.Rule;

import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import java.util.UUID;

/**
 * Тестовый HTTP-клиент создания, чтения, замены и удаления тарифных правил. Используется с WireMock для
 * проверки сетевого запроса и ответа; бизнес-операции сервиса и хранение данных не реализует.
 */
public final class TariffRulesHttpClient {
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Создаёт клиент для адреса WireMock текущего теста. Приложение TARIFFS и Spring не запускает.
     *
     * @param baseUrl HTTP-адрес WireMock, выделенный текущему тесту
     */
    public TariffRulesHttpClient(String baseUrl) { http = RestClient.builder().baseUrl(baseUrl).build(); }

    /**
     * Отправляет все условия создания через POST, ожидает HTTP 201 и возвращает прочитанное правило. Запись в
     * БД этим вызовом не проверяется.
     *
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    public Rule create(RuleRequest request) { return exchange(HttpMethod.POST, "/tariffs/rules", request, 201, Rule.class); }

    /**
     * Читает правило через GET по UUID, ожидая HTTP 200. При ошибочном ответе передаёт его статус и код в
     * исключении.
     *
     * @param id UUID тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    public Rule get(UUID id) { return exchange(HttpMethod.GET, "/tariffs/rules/" + id, null, 200, Rule.class); }

    /**
     * Отправляет полную замену через PUT, включая поле {@code upperBound} со значением {@code null}. Ожидает
     * HTTP 200 и возвращает прочитанное правило.
     *
     * @param id UUID тарифного правила
     * @param request полный набор условий тарифного правила
     * @return правило с UUID, версией и полным набором условий
     */
    public Rule update(UUID id, RuleRequest request) { return exchange(HttpMethod.PUT, "/tariffs/rules/" + id, request, 200, Rule.class); }

    /**
     * Отправляет DELETE по UUID и ожидает HTTP 204. Возвращаемого результата нет.
     *
     * @param id UUID тарифного правила
     */
    public void delete(UUID id) { exchange(HttpMethod.DELETE, "/tariffs/rules/" + id, null, 204, Void.class); }

    /**
     * Читает список правил через GET, ожидая HTTP 200. Сценарий может сравнить его до и после отказа создания.
     *
     * @return список текущих тарифных правил
     */
    public RulesResponse list() { return exchange(HttpMethod.GET, "/tariffs/rules", null, 200, RulesResponse.class); }

    /**
     * Выполняет один HTTP-запрос и сравнивает статус с ожидаемым. При совпадении читает модель ответа или
     * возвращает {@code null} для удаления. При другом статусе читает код и пояснение ошибки и выбрасывает
     * {@code TariffApiException} с тем же статусом.
     *
     * @param <T> тип модели, которую нужно прочитать из JSON
     * @param method HTTP-метод запроса
     * @param path путь HTTP-запроса
     * @param body условия правила для тела запроса или {@code null}, если тело не требуется
     * @param expected ожидаемый HTTP-статус
     * @param type Java-класс модели, в которую нужно прочитать JSON
     * @return модель ответа указанного типа или {@code null} для DELETE
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
