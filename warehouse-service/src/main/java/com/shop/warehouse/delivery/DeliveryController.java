package com.shop.warehouse.delivery;

import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import static com.shop.warehouse.delivery.DeliveryModels.*;

/** Store-scoped delivery diagnostics and the explicitly educational supplier publisher; no inventory writes occur here. */
@RestController
public class DeliveryController {
    private final DeliveryStore store;
    private final DeliveryCodec codec;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    /** Receives committed delivery views, strict parsing and an acknowledged Kafka publisher. */
    public DeliveryController(DeliveryStore store, DeliveryCodec codec, KafkaTemplate<String, String> kafka, Clock clock) {
        this.store = store;
        this.codec = codec;
        this.kafka = kafka;
        this.clock = clock;
    }

    /** Reads only the identified store/delivery, returning NOT_FOUND for absent or differently scoped records. */
    @GetMapping("/stores/{storeId}/deliveries/{deliveryId}")
    public View get(@PathVariable String storeId, @PathVariable String deliveryId) {
        return store.view(codec.identifier(storeId), codec.identifier(deliveryId));
    }

    /** Returns 202 after scheduling an immediate background attempt; an active attempt remains fenced by its original token. */
    @PostMapping("/stores/{storeId}/deliveries/{deliveryId}/retry-pricing")
    public ResponseEntity<View> retry(@PathVariable String storeId, @PathVariable String deliveryId) {
        return ResponseEntity.accepted().body(store.retry(codec.identifier(storeId), codec.identifier(deliveryId)));
    }

    /** Publishes a validated supplier event; 202 requires broker acknowledgement and does not promise acceptance or POSTED. */
    @PostMapping(value="/technical/deliveries", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Published> publish(@RequestBody String raw) {
        Accepted input = codec.decode(raw);
        if (input.rejection() != null)
            throw new DeliveryException(400, input.rejection().code(), input.rejection().message());
        try { kafka.send("logistics.deliveries", input.storeId(), raw).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception failure) { throw unavailable(); }
        return ResponseEntity.accepted().body(new Published(input.eventId(), input.storeId(), input.deliveryId(),
                "PUBLISHED", clock.instant()));
    }

    /** Reports ambiguous send failure safely; caller retries the same event/delivery identifiers. */
    private DeliveryException unavailable() {
        return new DeliveryException(503, "DEPENDENCY_UNAVAILABLE", "Delivery publication unavailable; reuse the same identifiers when retrying");
    }
}
