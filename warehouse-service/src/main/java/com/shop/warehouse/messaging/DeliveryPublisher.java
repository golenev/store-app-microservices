package com.shop.warehouse.messaging;

import com.shop.warehouse.exception.DeliveryException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** Публикует исходное событие поставщика без изменения идентификаторов и содержимого. */
@Component
public class DeliveryPublisher {
    private final KafkaTemplate<String, String> kafka;

    /**
     * Получает producer; конструктор не отправляет сообщения.
     *
     * @param kafka producer для отправки сообщений Kafka
     */
    public DeliveryPublisher(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    /**
     * Отправляет raw с ключом store и ждёт подтверждение не более пяти секунд вне SQL-транзакции.
     * Неопределённый результат возвращает 503; вызывающий клиент повторяет прежние идентификаторы. Прерывание
     * восстанавливает флаг потока.
     *
     * @param store идентификатор магазина
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public void publish(String store, String raw) {
        try { kafka.send("logistics.deliveries", store, raw).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception failure) { throw unavailable(); }
    }

    /**
     * Возвращает безопасную ошибку неопределённой публикации без адресов и сетевых подробностей.
     */
    private DeliveryException unavailable() {
        return new DeliveryException(503, "DEPENDENCY_UNAVAILABLE", "Delivery publication unavailable; reuse the same identifiers when retrying");
    }
}
