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

/** Выполняет ограниченные по времени запросы TARIFFS вне SQL-транзакции и проверяет финансовый ответ. */
@Component
public class TariffClient {
    private final RestClient http;
    private final DeliveryCodec codec;

    /**
     * Создаёт клиент baseUrl с тайм-аутом соединения 1 секунда и чтения 2 секунды; конструктор не отправляет
     * запросов.
     *
     * @param codec строгий разбор и сериализация протокола
     * @param baseUrl внутренний HTTP-адрес TARIFFS
     */
    public TariffClient(DeliveryCodec codec, @Value("${tariffs.base-url:http://localhost:6790}") String baseUrl) {
        this.codec = codec;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Получает тариф строки line для cityId вне SQL-транзакции, проверяет три поля ответа и вычисляет цену
     * HALF_UP. 404/409 сохраняет как ошибки выбора; неверный ответ или сеть вызывает DEPENDENCY_UNAVAILABLE.
     *
     * @param line строка поставки для расчёта тарифа
     * @param cityId идентификатор города выбора тарифа
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
     * Возвращает безопасную ошибку недоступного или неверного тарифа без URL и сетевых подробностей.
     */
    private DeliveryException unavailable() {
        return new DeliveryException(503, "DEPENDENCY_UNAVAILABLE", "Tariff quote unavailable or invalid");
    }
}
