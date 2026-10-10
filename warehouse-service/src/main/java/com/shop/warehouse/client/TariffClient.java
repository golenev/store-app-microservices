package com.shop.warehouse.client;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Line;
import com.shop.warehouse.exception.DeliveryException;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.math.*;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Запрашивает наценку у TARIFFS по HTTP и вычисляет продажную цену строки поставки. Сеть вызывается вне
 * транзакции PostgreSQL, ответ сервиса проверяется перед использованием.
 */
@Component
public class TariffClient {
    private final RestClient http;
    private final DeliveryCodec codec;

    /**
     * Создаёт HTTP-клиент для заданного адреса TARIFFS. Ожидание подключения ограничивает одной секундой,
     * ожидание ответа — двумя; запрос при создании не отправляет.
     *
     * @param codec проверка событий поставок и преобразование сохранённых моделей
     * @param baseUrl базовый HTTP-адрес сервиса
     */
    public TariffClient(DeliveryCodec codec, @Value("${tariffs.base-url:http://localhost:6790}") String baseUrl) {
        this.codec = codec;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Запрашивает тариф по строке поставки и городу. Проверяет наценку, UUID и версию правила, затем вычисляет
     * закупочную цену, умноженную на один плюс наценка. Округляет до копеек по {@code HALF_UP}: при половине
     * копейки округляет вверх. Возвращает строку с расчётом. HTTP 404 и 409 преобразует в ошибки отсутствия
     * или неоднозначности тарифа, сбой сети или неверный ответ — в {@code DEPENDENCY_UNAVAILABLE}. Превышение
     * допустимого размера цены вызывает {@code VALIDATION_ERROR}.
     *
     * @param line строка поставки с закупочной ценой для расчёта тарифа
     * @param cityId идентификатор города, для которого выбирается тариф
     * @return строка поставки с рассчитанными наценкой и продажной ценой
     */
    public Line price(Line line, String cityId) {
        try {
            String raw = http.get().uri(builder -> builder.path("/tariffs/quote")
                    .queryParam("productType", line.productType()).queryParam("purchasePrice", line.purchasePrice())
                    .queryParam("currency", line.currency()).queryParam("cityId", cityId).build()).retrieve().body(String.class);
            JsonNode result = codec.read(raw);
            if (!result.isObject() || result.size() != 3 || !result.path("markupRate").isTextual()
                    || !result.path("markupRate").textValue().matches(DeliveryCodec.RATE)
                    || !result.path("tariffRuleId").isTextual() || !result.path("tariffVersion").isIntegralNumber()
                    || !result.path("tariffVersion").canConvertToLong() || result.path("tariffVersion").longValue() < 1
                    || result.path("tariffVersion").longValue() > 9007199254740991L) throw unavailable();
            var rule = codec.uuid(result.get("tariffRuleId").textValue());
            BigDecimal rate = new BigDecimal(result.get("markupRate").textValue()).setScale(6);
            String sale = new BigDecimal(line.purchasePrice()).multiply(BigDecimal.ONE.add(rate))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
            if (!sale.matches(DeliveryCodec.MONEY))
                throw new DeliveryException(400, "VALIDATION_ERROR", "Calculated sale price exceeds the v1 money limit");
            return new Line(line.lineId(), line.productId(), line.productType(), line.shortName(), line.description(),
                    line.quantity(), line.purchasePrice(), line.currency(), rate.toPlainString(), rule,
                    result.get("tariffVersion").longValue(), sale);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404)
                throw new DeliveryException(404, "TARIFF_NOT_FOUND", "No tariff rule matches the delivery line");
            if (failure.getStatusCode().value() == 409)
                throw new DeliveryException(409, "TARIFF_AMBIGUOUS", "Multiple tariff rules match the delivery line");
            throw unavailable();
        } catch (RestClientException failure) { throw unavailable(); }
        catch (DeliveryException failure) {
            if (failure.getMessage().startsWith("Calculated sale")) throw failure;
            throw unavailable();
        }
    }

    /**
     * Создаёт ошибку HTTP 503 с кодом {@code DEPENDENCY_UNAVAILABLE} для недоступного сервиса или неверного
     * ответа тарифа. Адрес сервиса и сетевые подробности в пояснение не входят.
     *
     * @return исключение поставки с подготовленными статусом, кодом и пояснением
     */
    private DeliveryException unavailable() {
        return new DeliveryException(503, "DEPENDENCY_UNAVAILABLE", "Tariff quote unavailable or invalid");
    }
}
