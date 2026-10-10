package com.shop.warehouse.messaging;

import com.shop.warehouse.service.DeliveryService;
import com.shop.warehouse.model.OutboxWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Optional;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.concurrent.TimeUnit;

/** Публикует сохранённые GoodsPosted вне SQL-транзакции и фиксирует результат по токену владельца. */
@Component
@ConditionalOnProperty(name="warehouse.workers.enabled", havingValue="true", matchIfMissing=true)
public class GoodsPostedPublisher {
    private static final Logger log = LoggerFactory.getLogger(GoodsPostedPublisher.class);
    private final DeliveryService store;
    private final KafkaTemplate<String, String> kafka;

    /**
     * Получает транзакционный сервис и внешний адаптер; конструктор не выполняет запросов.
     *
     * @param store сервис транзакций поставок и outbox
     * @param kafka producer для отправки сообщений Kafka
     */
    public GoodsPostedPublisher(DeliveryService store, KafkaTemplate<String, String> kafka) {
        this.store = store;
        this.kafka = kafka;
    }

    /**
     * Запускает отправку сохранённого события по расписанию. Сбой сохраняет ожидание и следующую попытку в БД.
     */
    @Scheduled(fixedDelayString="${warehouse.sender-poll-ms:500}")
    public void senderTick() {
        try { sendOne(); }
        catch (Exception failure) { log.error("Outbox worker failed; persisted lease will recover", failure); }
    }

    /**
     * Отправляет одно сохранённое событие и ждёт подтверждение брокера не более пяти секунд. Ошибка последующей
     * записи в БД допускает повтор того же содержимого.
     */
    public void sendOne() {
        Optional<OutboxWork> pending = store.claimOutbox();
        if (pending.isEmpty()) return;
        OutboxWork work = pending.get();
        try { kafka.send("warehouse.goods-posted", work.storeId(), work.payload()).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); store.failedSend(work); return; }
        catch (Exception failure) { store.failedSend(work); return; }
        store.published(work);
    }
}
