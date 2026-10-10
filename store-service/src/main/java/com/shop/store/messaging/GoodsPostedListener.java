package com.shop.store.messaging;

import com.shop.store.service.GoodsReceiptService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Получает сообщения Kafka и передаёт их сервису приёмки. Завершает обработку только после сохранения
 * результата или причины отказа в БД.
 */
@Component
public class GoodsPostedListener {
    private final GoodsReceiptService receiver;

    /**
     * Подключает сервис приёмки, который сохраняет результат обработки сообщения в транзакции.
     *
     * @param receiver приёмка поставок и сохранение остатков магазина
     */
    public GoodsPostedListener(GoodsReceiptService receiver) { this.receiver = receiver; }

    /**
     * Передаёт сервису исходное сообщение, ключ и координаты в Kafka. Успешно возвращается после сохранения
     * результата в БД. Если БД недоступна, ошибка выходит из метода и Kafka повторяет обработку.
     *
     * @param record исходное сообщение Kafka с ключом, содержимым и координатами
     */
    @KafkaListener(topics="warehouse.goods-posted",groupId="store-goods-v1",autoStartup="${store.listener.enabled:true}")
    public void receive(ConsumerRecord<String,String> record) {
        receiver.receive(record.topic(),record.partition(),record.offset(),record.key(),record.value());
    }
}
