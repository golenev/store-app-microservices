package com.shop.warehouse.messaging;

import com.shop.warehouse.exception.DeliveryException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/**
 * Отправляет событие поставщика в Kafka, сохраняя исходные идентификаторы и содержимое.
 */
@Component
public class DeliveryPublisher {
    private final KafkaTemplate<String, String> kafka;

    /**
     * Подключает отправку сообщений Kafka.
     *
     * @param kafka отправка строковых сообщений Kafka с ключом магазина
     */
    public DeliveryPublisher(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    /**
     * Отправляет исходный JSON с ключом магазина и ждёт подтверждения Kafka не более пяти секунд.
     * SQL-транзакцию не открывает. При сбое или неизвестном результате выдаёт HTTP 503: клиент может повторить
     * запрос с прежними идентификаторами. При прерывании восстанавливает признак прерывания потока.
     *
     * @param store идентификатор магазина
     * @param raw исходный JSON события поставщика без изменения идентификаторов и содержимого
     */
    public void publish(String store, String raw) {
        try { kafka.send("logistics.deliveries", store, raw).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception failure) { throw unavailable(); }
    }

    /**
     * Создаёт ошибку HTTP 503 для публикации, результат которой не удалось подтвердить. Адрес брокера и
     * сетевые подробности в сообщение не входят.
     *
     * @return исключение поставки с подготовленными статусом, кодом и пояснением
     */
    private DeliveryException unavailable() {
        return new DeliveryException(503, "DEPENDENCY_UNAVAILABLE", "Delivery publication unavailable; reuse the same identifiers when retrying");
    }
}
