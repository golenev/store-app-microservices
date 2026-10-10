package com.shop.warehouse.messaging;

import com.shop.warehouse.service.DeliveryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Получает сообщения Kafka и передаёт их сервису приёмки. Завершает обработку только после сохранения
 * результата или причины отказа в БД.
 */
@Component
public class DeliveryReceivedListener {
    private final DeliveryService store;

    /**
     * Подключает сервис приёмки, который сохраняет результат обработки сообщения в транзакции.
     *
     * @param store транзакции приёмки, расчёта и очереди событий поставок
     */
    public DeliveryReceivedListener(DeliveryService store) { this.store = store; }

    /**
     * Передаёт сервису исходное сообщение, ключ и координаты в Kafka. Успешно возвращается после сохранения
     * результата в БД. Если БД недоступна, ошибка выходит из метода и Kafka повторяет обработку.
     *
     * @param record исходное сообщение Kafka с ключом, содержимым и координатами
     */
    @KafkaListener(topics="logistics.deliveries", groupId="warehouse-deliveries-v1",
            autoStartup="${warehouse.listener.enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        store.receive(record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
