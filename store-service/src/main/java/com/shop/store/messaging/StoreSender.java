package com.shop.store.messaging;

import com.shop.store.model.OutboxWork;
import com.shop.store.service.SubmissionTransactionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Публикует неизменяемые принятые заявки вне транзакции остатков. Повторная отправка сохраняет eventId. */
@Component
@ConditionalOnProperty(name="store.sender.enabled",havingValue="true",matchIfMissing=true)
public class StoreSender {
    private static final Logger log=LoggerFactory.getLogger(StoreSender.class);
    private final SubmissionTransactionService store;
    private final KafkaTemplate<String,String> kafka;
    /**
     * Получает хранилище заявок и Kafka producer. Конструктор не выполняет запросы к БД или брокеру.
     *
     * @param store сервис транзакций оформления и outbox
     * @param kafka producer для отправки сообщений Kafka
     */
    public StoreSender(SubmissionTransactionService store,KafkaTemplate<String,String> kafka) { this.store=store; this.kafka=kafka; }
    /**
     * Запускает отправку после старта и повторяет её по расписанию. Ошибка оставляет работу восстанавливаемой
     * после истечения срока захвата.
     */
    @Scheduled(fixedDelayString="${store.sender-poll-ms:500}")
    public void tick() {
        try { sendOne(); }
        catch(Exception failure) { log.error("STORE sender failed; persisted outbox will recover",failure); }
    }
    /**
     * Отправляет одно сохранённое событие вне SQL-транзакции. При потере подтверждения или ошибке записи
     * допускает повтор без нового списания.
     */
    public void sendOne() {
        var pending=store.claimOutbox(); if(pending.isEmpty()) return;
        OutboxWork work=pending.get();
        try { kafka.send("store.order-submitted",work.storeId(),work.payload()).get(5,TimeUnit.SECONDS); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); store.failedSend(work); return; }
        catch(Exception failure) { store.failedSend(work); return; }
        store.published(work);
    }
}
