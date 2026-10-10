package com.shop.store.messaging;

import com.shop.store.service.GoodsReceiptService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Передаёт входное Kafka-событие сервису; успешный возврат следует после фиксации результата в БД. */
@Component
public class GoodsPostedListener {
    private final GoodsReceiptService receiver;

    /**
     * Получает транзакционный сервис приёмки; конструктор не обращается к Kafka или БД.
     *
     * @param receiver транзакционный сервис приёмки
     */
    public GoodsPostedListener(GoodsReceiptService receiver) { this.receiver = receiver; }

    /**
     * Передаёт исходный record с ключом и координатами сервису приёмки. Успешный возврат следует после фиксации
     * результата; сбой БД требует повтора Kafka.
     *
     * @param record исходное сообщение Kafka с ключом, содержимым и координатами
     */
    @KafkaListener(topics="warehouse.goods-posted",groupId="store-goods-v1",autoStartup="${store.listener.enabled:true}")
    public void receive(ConsumerRecord<String,String> record) {
        receiver.receive(record.topic(),record.partition(),record.offset(),record.key(),record.value());
    }
}
