package com.shop.warehouse.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.shop.warehouse.delivery.DeliveryModels.*;

/** Обрабатывает расчёт и отправку по расписанию. Сохранённый срок захвата и токен защищают от пересекающихся попыток и обеспечивают восстановление. */
@Component
@ConditionalOnProperty(name="warehouse.workers.enabled", havingValue="true", matchIfMissing=true)
public class DeliveryWorkers {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorkers.class);
    private final DeliveryStore store;
    private final TariffClient tariffs;
    private final KafkaTemplate<String, String> kafka;

    /** Получает хранилище, клиент тарифов и Kafka producer. Конструктор не выполняет внешних запросов. */
    public DeliveryWorkers(DeliveryStore store, TariffClient tariffs, KafkaTemplate<String, String> kafka) {
        this.store = store;
        this.tariffs = tariffs;
        this.kafka = kafka;
    }

    /** Запускает наступившие попытки расчёта. Сохранённый захват позволяет восстановиться без ручного повтора. */
    @Scheduled(fixedDelayString="${warehouse.pricing-poll-ms:500}")
    public void pricingTick() {
        try { priceOne(); }
        catch (Exception failure) { log.error("Pricing worker failed; persisted lease will recover", failure); }
    }

    /** Захватывает одну поставку и продлевает владение перед каждой строкой. Результаты фиксируются одной транзакцией; потерявшая владение попытка прекращает обработку. */
    public void priceOne() {
        Optional<PricingWork> pending = store.claimPricing();
        if (pending.isEmpty()) return;
        PricingWork work = pending.get();
        try {
            List<Line> results = new ArrayList<>();
            for (Line line : work.items()) {
                if (!store.renew(work)) return;
                results.add(tariffs.price(line, work.cityId()));
            }
            store.post(work, results);
        } catch (DeliveryException failure) {
            store.failedPricing(work, new Failure(failure.code(), failure.getMessage()));
        }
    }

    /** Запускает отправку сохранённого события по расписанию. Сбой сохраняет ожидание и следующую попытку в БД. */
    @Scheduled(fixedDelayString="${warehouse.sender-poll-ms:500}")
    public void senderTick() {
        try { sendOne(); }
        catch (Exception failure) { log.error("Outbox worker failed; persisted lease will recover", failure); }
    }

    /** Отправляет одно сохранённое событие и ждёт подтверждение брокера не более пяти секунд. Ошибка последующей записи в БД допускает повтор того же содержимого. */
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
