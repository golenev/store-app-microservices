package com.shop.warehouse.messaging;

import com.shop.warehouse.service.DeliveryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Передаёт входное Kafka-событие сервису; успешный возврат следует после фиксации результата в БД. */
@Component
public class DeliveryReceivedListener {
    private final DeliveryService store;

    /**
     * Получает транзакционный сервис приёмки; конструктор не обращается к Kafka или БД.
     *
     * @param store сервис транзакций поставок и outbox
     */
    public DeliveryReceivedListener(DeliveryService store) { this.store = store; }

    /**
     * Передаёт исходный record с ключом и координатами сервису приёмки. Успешный возврат следует после фиксации
     * результата; сбой БД требует повтора Kafka.
     *
     * @param record исходное сообщение Kafka с ключом, содержимым и координатами
     */
    @KafkaListener(topics="logistics.deliveries", groupId="warehouse-deliveries-v1",
            autoStartup="${warehouse.listener.enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        store.receive(record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
