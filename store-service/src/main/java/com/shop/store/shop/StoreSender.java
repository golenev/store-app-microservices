package com.shop.store.shop;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.TimeUnit;
import static com.shop.store.shop.ShopModels.*;

/** Publishes immutable accepted operations independently of inventory transactions, allowing physical replay with unchanged eventId. */
@Component
@ConditionalOnProperty(name="store.sender.enabled",havingValue="true",matchIfMissing=true)
public class StoreSender {
    private static final Logger log=LoggerFactory.getLogger(StoreSender.class);
    private final SubmissionStore store;
    private final KafkaTemplate<String,String> kafka;
    /** Optional persisted gates are injected only by the explicit e2e profile; normal deployments have no control surface. */
    @Autowired(required=false)
    private StoreE2eGate e2eGate;
    /** Receives short-transaction persisted work and the bounded acknowledgement producer; constructor does no I/O. */
    public StoreSender(SubmissionStore store,KafkaTemplate<String,String> kafka) { this.store=store; this.kafka=kafka; }
    /** Retries automatically after startup; failed completion leaves a persisted lease reclaimable after expiry. */
    @Scheduled(fixedDelayString="${store.sender-poll-ms:500}")
    public void tick() {
        try { sendOne(); }
        catch(Exception failure) { log.error("STORE sender failed; persisted outbox will recover",failure); }
    }
    /** Sends one exact stored event outside SQL transactions; ack loss or completion failure may replay it without new deductions. */
    public void sendOne() {
        var pending=store.claimOutbox(); if(pending.isEmpty()) return;
        OutboxWork work=pending.get();
        if(e2eGate!=null && !e2eGate.permits(work.storeId(),work.eventId().toString(),"BEFORE_PUBLISH")) return;
        try { kafka.send("store.order-submitted",work.storeId(),work.payload()).get(5,TimeUnit.SECONDS); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); store.failedSend(work); return; }
        catch(Exception failure) { store.failedSend(work); return; }
        if(e2eGate!=null && !e2eGate.permits(work.storeId(),work.eventId().toString(),"AFTER_ACK")) return;
        store.published(work);
    }
}
