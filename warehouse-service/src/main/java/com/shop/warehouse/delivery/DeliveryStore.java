package com.shop.warehouse.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.shop.warehouse.delivery.DeliveryModels.*;

/** Owns short PostgreSQL transactions; HTTP and Kafka sending always happen after these methods return. */
@Service
public class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final DeliveryCodec codec;
    private final Clock clock;
    private final long leaseMillis;

    /** Receives transaction-aware SQL, strict serialization and the configurable stale-worker recovery interval. */
    public DeliveryStore(JdbcTemplate jdbc, DeliveryCodec codec, Clock clock,
                         @Value("${warehouse.lease-ms:30000}") long leaseMillis) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.clock = clock;
        this.leaseMillis = leaseMillis;
    }

    /** Saves a result or diagnostic before Kafka acknowledgement; short ingress lock serializes event/delivery deduplication. */
    @Transactional
    public void receive(String topic, int partition, long offset, String key, String raw) {
        jdbc.execute("SELECT pg_advisory_xact_lock(731004)");
        Accepted input;
        try { input = codec.decode(raw); }
        catch (DeliveryException failure) { diagnostic(topic, partition, offset, raw, failure.code(), failure.getMessage()); return; }
        if (!input.storeId().equals(key)) {
            diagnostic(topic, partition, offset, raw, "VALIDATION_ERROR", "Kafka key must equal storeId"); return;
        }
        if (jdbc.queryForObject("SELECT count(*) FROM stores WHERE store_id=?", Integer.class, input.storeId()) == 0) {
            diagnostic(topic, partition, offset, raw, "NOT_FOUND", "Unknown store"); return;
        }
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM received_events WHERE event_id=?", input.eventId());
        if (!events.isEmpty()) {
            Map<String, Object> previous = events.getFirst();
            if (!input.storeId().equals(previous.get("store_id")) || !input.deliveryId().equals(previous.get("delivery_id"))
                    || !input.fingerprint().equals(previous.get("fingerprint")))
                diagnostic(topic, partition, offset, raw, "DELIVERY_CONTENT_CONFLICT", "eventId has different business content");
            return;
        }
        List<String> fingerprints = jdbc.queryForList("SELECT fingerprint FROM deliveries WHERE store_id=? AND delivery_id=?",
                String.class, input.storeId(), input.deliveryId());
        if (!fingerprints.isEmpty() && !fingerprints.getFirst().equals(input.fingerprint())) {
            diagnostic(topic, partition, offset, raw, "DELIVERY_CONTENT_CONFLICT", "deliveryId has different business content"); return;
        }
        if (fingerprints.isEmpty()) {
            List<Long> sequences = jdbc.queryForList("""
                    UPDATE stores SET delivery_sequence=delivery_sequence+1
                    WHERE store_id=? AND delivery_sequence < 9007199254740991 RETURNING delivery_sequence
                    """, Long.class, input.storeId());
            if (sequences.isEmpty()) {
                diagnostic(topic, partition, offset, raw, "DEPENDENCY_UNAVAILABLE", "Store delivery sequence is exhausted"); return;
            }
            long sequence = sequences.getFirst();
            Instant now = clock.instant();
            jdbc.update("""
                    INSERT INTO deliveries(store_id,delivery_id,delivery_sequence,fingerprint,state,received_at,next_attempt_at,last_error,original_payload)
                    VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """, input.storeId(), input.deliveryId(), sequence, input.fingerprint(),
                    input.rejection() == null ? "WAITING_PRICING" : "REJECTED", timestamp(now),
                    input.rejection() == null ? timestamp(now) : null,
                    input.rejection() == null ? null : codec.json(input.rejection()), codec.json(input.payload()));
            for (InputLine line : input.items()) jdbc.update("""
                    INSERT INTO delivery_items(store_id,delivery_id,line_id,product_id,product_type,short_name,description,quantity,purchase_price,currency)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, input.storeId(), input.deliveryId(), line.lineId(), line.productId(), line.productType(),
                    line.shortName(), line.description(), line.quantity(), new BigDecimal(line.purchasePrice()), line.currency());
        }
        jdbc.update("INSERT INTO received_events(event_id,store_id,delivery_id,fingerprint) VALUES(?,?,?,?)",
                input.eventId(), input.storeId(), input.deliveryId(), input.fingerprint());
    }

    /** Saves poison/conflict input once per Kafka coordinate; storage failures propagate so offsets are not acknowledged. */
    private void diagnostic(String topic, int partition, long offset, String raw, String code, String message) {
        jdbc.update("""
                INSERT INTO delivery_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """, topic, partition, offset, timestamp(clock.instant()), code, message, raw);
    }

    /** Returns a store-scoped immutable view; REJECTED exposes raw parsed payload instead of invented valid lines. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View view(String store, String delivery) {
        List<View> rows = jdbc.query("SELECT * FROM deliveries WHERE store_id=? AND delivery_id=?", (row, number) ->
                new View(store, delivery, row.getLong("delivery_sequence"), row.getString("state"), instant(row, "received_at"),
                        instant(row, "posted_at"), row.getLong("attempt_count"), instant(row, "next_attempt_at"),
                        row.getString("last_error") == null ? null : codec.restore(row.getString("last_error"), Failure.class),
                        lines(store, delivery), "REJECTED".equals(row.getString("state")) ? codec.read(row.getString("original_payload")) : null), store, delivery);
        if (rows.isEmpty()) throw new DeliveryException(404, "NOT_FOUND", "Delivery not found");
        return rows.getFirst();
    }

    /** Reads ordered SQL lines, keeping monetary scale and omitting pricing fields until the entire delivery is posted. */
    private List<Line> lines(String store, String delivery) {
        return jdbc.query("SELECT * FROM delivery_items WHERE store_id=? AND delivery_id=? ORDER BY line_id", (row, number) -> {
            BigDecimal rate = row.getBigDecimal("markup_rate"), sale = row.getBigDecimal("sale_price");
            return new Line(row.getString("line_id"), row.getString("product_id"), row.getString("product_type"),
                    row.getString("short_name"), row.getString("description"), row.getInt("quantity"),
                    row.getBigDecimal("purchase_price").toPlainString(), row.getString("currency"),
                    rate == null ? null : rate.toPlainString(), row.getObject("tariff_rule_id", UUID.class),
                    rate == null ? null : row.getLong("tariff_version"), sale == null ? null : sale.toPlainString());
        }, store, delivery);
    }

    /** Accelerates WAITING_PRICING only; an existing worker lease is preserved and never bypassed by manual retry. */
    @Transactional
    public View retry(String store, String delivery) {
        jdbc.queryForList("SELECT state FROM deliveries WHERE store_id=? AND delivery_id=? FOR UPDATE", store, delivery);
        View previous = view(store, delivery);
        if (!previous.state().equals("WAITING_PRICING"))
            throw new DeliveryException(409, "DELIVERY_NOT_WAITING_PRICING", "Delivery is not waiting for pricing");
        jdbc.update("UPDATE deliveries SET next_attempt_at=? WHERE store_id=? AND delivery_id=?",
                timestamp(clock.instant()), store, delivery);
        return view(store, delivery);
    }

    /** Claims one due/expired delivery and commits attempt count before HTTP; SKIP LOCKED and token fence concurrent workers. */
    @Transactional
    public Optional<PricingWork> claimPricing() {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.store_id,d.delivery_id,s.city,d.attempt_count FROM deliveries d JOIN stores s USING(store_id)
                WHERE d.state='WAITING_PRICING' AND d.next_attempt_at<=? AND (d.lease_until IS NULL OR d.lease_until<=?)
                ORDER BY d.next_attempt_at,d.store_id,d.delivery_sequence LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """, timestamp(now), timestamp(now));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        String store = (String) row.get("store_id"), delivery = (String) row.get("delivery_id");
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE deliveries SET lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE store_id=? AND delivery_id=?",
                token, timestamp(now.plusMillis(leaseMillis)), store, delivery);
        return Optional.of(new PricingWork(store, delivery, (String) row.get("city"), token,
                ((Number) row.get("attempt_count")).longValue() + 1, lines(store, delivery)));
    }

    /** Renews before each HTTP line so large deliveries do not expire; a stolen token aborts the old worker. */
    @Transactional
    public boolean renew(PricingWork work) {
        return jdbc.update("UPDATE deliveries SET lease_until=? WHERE store_id=? AND delivery_id=? AND lease_token=? AND state='WAITING_PRICING'",
                timestamp(clock.instant().plusMillis(leaseMillis)), work.storeId(), work.deliveryId(), work.token()) == 1;
    }

    /** Atomically posts every line and one serialized GoodsPosted; a reclaimed worker token cannot change any data. */
    @Transactional
    public boolean post(PricingWork work, List<Line> priced) {
        List<String> tokens = jdbc.queryForList("SELECT lease_token::text FROM deliveries WHERE store_id=? AND delivery_id=? FOR UPDATE",
                String.class, work.storeId(), work.deliveryId());
        if (tokens.isEmpty() || !work.token().toString().equals(tokens.getFirst())) return false;
        View before = view(work.storeId(), work.deliveryId());
        Instant now = clock.instant();
        Instant posted = now.isBefore(before.receivedAt()) ? before.receivedAt() : now;
        for (Line line : priced) jdbc.update("""
                UPDATE delivery_items SET markup_rate=?,tariff_rule_id=?,tariff_version=?,sale_price=?
                WHERE store_id=? AND delivery_id=? AND line_id=?
                """, new BigDecimal(line.markupRate()), line.tariffRuleId(), line.tariffVersion(), new BigDecimal(line.salePrice()),
                work.storeId(), work.deliveryId(), line.lineId());
        UUID event = UUID.randomUUID();
        GoodsEvent goods = new GoodsEvent(event, "GoodsPosted", 1, posted, work.storeId(),
                new GoodsPayload(work.deliveryId(), before.deliverySequence(), before.receivedAt(), posted, List.copyOf(priced)));
        jdbc.update("INSERT INTO warehouse_outbox(event_id,store_id,delivery_id,payload,next_attempt_at) VALUES(?,?,?,?,?)",
                event, work.storeId(), work.deliveryId(), codec.json(goods), timestamp(posted));
        jdbc.update("""
                UPDATE deliveries SET state='POSTED',posted_at=?,next_attempt_at=NULL,last_error=NULL,lease_token=NULL,lease_until=NULL
                WHERE store_id=? AND delivery_id=?
                """, timestamp(posted), work.storeId(), work.deliveryId());
        return true;
    }

    /** Persists dependency/price failure and bounded backoff only for the current attempt; no partial lines are written. */
    @Transactional
    public void failedPricing(PricingWork work, Failure failure) {
        jdbc.update("""
                UPDATE deliveries SET last_error=?::jsonb,next_attempt_at=?,lease_token=NULL,lease_until=NULL
                WHERE store_id=? AND delivery_id=? AND lease_token=? AND state='WAITING_PRICING'
                """, codec.json(failure), timestamp(clock.instant().plusMillis(backoff(work.attemptCount()))),
                work.storeId(), work.deliveryId(), work.token());
    }

    /** Claims one pending event without holding SQL locks while Kafka confirms it; expired claims recover after restart. */
    @Transactional
    public Optional<OutboxWork> claimOutbox() {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT event_id,store_id,payload,attempt_count FROM warehouse_outbox
                WHERE publication_status='PENDING' AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, timestamp(now), timestamp(now));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        UUID event = (UUID) row.get("event_id"), token = UUID.randomUUID();
        jdbc.update("UPDATE warehouse_outbox SET lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE event_id=?",
                token, timestamp(now.plusMillis(leaseMillis)), event);
        return Optional.of(new OutboxWork(event, (String) row.get("store_id"), (String) row.get("payload"), token,
                ((Number) row.get("attempt_count")).longValue() + 1));
    }

    /** Records broker acknowledgement only for the current token; a missing update leaves immutable data safe for replay. */
    @Transactional
    public void published(OutboxWork work) {
        jdbc.update("""
                UPDATE warehouse_outbox SET publication_status='PUBLISHED',published_at=?,last_error=NULL,lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND lease_token=? AND publication_status='PENDING'
                """, timestamp(clock.instant()), work.eventId(), work.token());
    }

    /** Keeps the original event pending after send failure; an uncertain acknowledgement may lead to physical duplicate Kafka records. */
    @Transactional
    public void failedSend(OutboxWork work) {
        jdbc.update("""
                UPDATE warehouse_outbox SET last_error='Kafka publication unavailable',next_attempt_at=?,lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND lease_token=? AND publication_status='PENDING'
                """, timestamp(clock.instant().plusMillis(backoff(work.attemptCount()))), work.eventId(), work.token());
    }

    /** Uses capped exponential delay, keeping long-lived missing-rule deliveries eligible without infinite growth. */
    private long backoff(long attempt) { return Math.min(60000L, 1000L << Math.min(6L, Math.max(0L, attempt - 1))); }

    /** Converts nullable UTC timestamps to JDBC values without local-zone interpretation. */
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    /** Restores nullable database timestamps as UTC instants for protocol views. */
    private Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
