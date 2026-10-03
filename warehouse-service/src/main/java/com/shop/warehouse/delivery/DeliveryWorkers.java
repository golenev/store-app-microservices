package com.shop.warehouse.delivery;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.shop.warehouse.delivery.DeliveryModels.*;

/** One scheduled worker per application, with persisted lease fences for restart and accidental overlapping attempts. */
@Component
@ConditionalOnProperty(name="warehouse.workers.enabled", havingValue="true", matchIfMissing=true)
public class DeliveryWorkers {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorkers.class);
    private final DeliveryStore store;
    private final TariffClient tariffs;
    private final KafkaTemplate<String, String> kafka;

    /** Receives short-transaction storage and clients; constructor performs no external requests. */
    public DeliveryWorkers(DeliveryStore store, TariffClient tariffs, KafkaTemplate<String, String> kafka) {
        this.store = store;
        this.tariffs = tariffs;
        this.kafka = kafka;
    }

    /** Automatically processes due pricing; persisted leases recover failures without a user-triggered retry. */
    @Scheduled(fixedDelayString="${warehouse.pricing-poll-ms:500}")
    public void pricingTick() {
        try { priceOne(); }
        catch (Exception failure) { log.error("Pricing worker failed; persisted lease will recover", failure); }
    }

    /** Claims one delivery, renews ownership per line and posts all results together; stale workers discard computed values. */
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

    /** Sends one committed immutable event; failures retain PENDING and schedule a bounded persisted retry. */
    @Scheduled(fixedDelayString="${warehouse.sender-poll-ms:500}")
    public void senderTick() {
        try { sendOne(); }
        catch (Exception failure) { log.error("Outbox worker failed; persisted lease will recover", failure); }
    }

    /** Waits at most five seconds for broker acknowledgement; subsequent DB failure deliberately allows replay of the same payload. */
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
