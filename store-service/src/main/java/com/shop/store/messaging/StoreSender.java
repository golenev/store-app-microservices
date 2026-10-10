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

/**
 * Отправляет сохранённые события {@code OrderSubmitted} в Kafka после завершения транзакции БД. При
 * повторной отправке сохраняет идентификатор и содержимое события.
 */
@Component
@ConditionalOnProperty(name="store.sender.enabled",havingValue="true",matchIfMissing=true)
public class StoreSender {
    private static final Logger log=LoggerFactory.getLogger(StoreSender.class);
    private final SubmissionTransactionService store;
    private final KafkaTemplate<String,String> kafka;
    /**
     * Подключает операции с очередью событий в БД и отправку сообщений Kafka.
     *
     * @param store транзакции оформления и операции с очередью событий
     * @param kafka отправка строковых сообщений Kafka с ключом магазина
     */
    public StoreSender(SubmissionTransactionService store,KafkaTemplate<String,String> kafka) { this.store=store; this.kafka=kafka; }
    /**
     * По расписанию запускает отправку одного события. Неожиданную ошибку пишет в журнал; запись в очереди
     * позволяет повторить отправку после освобождения или истечения срока владения.
     */
    @Scheduled(fixedDelayString="${store.sender-poll-ms:500}")
    public void tick() {
        try { sendOne(); }
        catch(Exception failure) { log.error("STORE sender failed; persisted outbox will recover",failure); }
    }
    /**
     * Берёт одно ожидающее событие и ждёт подтверждения Kafka не более пяти секунд. При сбое отправки
     * назначает повтор, при прерывании также восстанавливает признак прерывания потока. После подтверждения
     * отмечает событие в БД как отправленное. Если эта запись не удалась, событие может быть отправлено
     * повторно с прежним содержимым.
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
