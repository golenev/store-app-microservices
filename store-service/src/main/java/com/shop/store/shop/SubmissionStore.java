package com.shop.store.shop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import static com.shop.store.shop.ShopModels.*;

/** Short STORE transactions for acceptance and publication. Kafka requests never run inside these transactions. */
@Service
public class SubmissionStore {
    private final JdbcTemplate jdbc;
    private final ShopCodec codec;
    private final Clock clock;
    private final long leaseMs;

    /** Receives SQL, canonical serialization and UTC time; leases outlive the producer's three-second block plus five-second ack wait. */
    public SubmissionStore(JdbcTemplate jdbc,ShopCodec codec,Clock clock,@Value("${store.lease-ms:30000}") long leaseMs) {
        if(leaseMs<10000) throw new IllegalArgumentException("store.lease-ms must be at least 10000");
        this.jdbc=jdbc; this.codec=codec; this.clock=clock; this.leaseMs=leaseMs;
    }

    /**
     * Accepts one canonical request in a fresh transaction: cart lock, UUID-ordered stock locks, validation,
     * submission, deductions/expenses, immutable outbox and cart closure. A duplicate UNIQUE rolls everything
     * back before the facade rereads its winner. Matching accepted keys are checked before current cart state.
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Submission accept(String store,UUID cart,String key,long version,String fingerprint) {
        Optional<Submission> prior=existing(store,key,fingerprint);
        if(prior.isPresent()) return prior.get();
        List<Map<String,Object>> headers=jdbc.queryForList("SELECT state,version FROM carts WHERE store_id=? AND cart_id=? FOR UPDATE",store,cart);
        if(headers.isEmpty()) throw new ShopException(404,"NOT_FOUND","Cart not found");
        prior=existing(store,key,fingerprint);
        if(prior.isPresent()) return prior.get();
        Map<String,Object> header=headers.getFirst();
        if(!header.get("state").equals("OPEN")) throw new ShopException(409,"CART_ALREADY_SUBMITTED","Cart is already submitted");
        if(((Number)header.get("version")).longValue()!=version) throw new ShopException(409,"CART_VERSION_CONFLICT","Cart version has changed");
        if(version==ShopCodec.MAX_VERSION) throw new ShopException(400,"VALIDATION_ERROR","Cart version is exhausted");
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT i.stock_item_id,i.product_id,i.short_name,i.unit_price,i.available_quantity,c.quantity
                FROM cart_items c JOIN inventory i ON i.store_id=c.store_id AND i.stock_item_id=c.stock_item_id
                WHERE c.store_id=? AND c.cart_id=? ORDER BY i.stock_item_id FOR UPDATE OF i
                """,store,cart);
        // A competing request on another cart may have committed while this one waited for stock locks.
        prior=existing(store,key,fingerprint);
        if(prior.isPresent()) return prior.get();
        if(rows.isEmpty() || rows.size()>1000) throw new ShopException(400,"VALIDATION_ERROR","Cart must contain 1 to 1000 items");
        List<CartLine> lines=new ArrayList<>(); BigDecimal total=new BigDecimal("0.00");
        for(Map<String,Object> row:rows) {
            int quantity=((Number)row.get("quantity")).intValue();
            if(quantity>((Number)row.get("available_quantity")).intValue())
                throw new ShopException(409,"INSUFFICIENT_STOCK","Requested quantity exceeds current stock");
            BigDecimal price=(BigDecimal)row.get("unit_price"), amount=price.multiply(BigDecimal.valueOf(quantity));
            lines.add(new CartLine((UUID)row.get("stock_item_id"),(String)row.get("product_id"),(String)row.get("short_name"),
                    quantity,price.toPlainString(),amount.toPlainString()));
            total=total.add(amount);
        }
        if(!total.toPlainString().matches("(0|[1-9][0-9]{0,35})\\.[0-9]{2}"))
            throw new ShopException(400,"VALIDATION_ERROR","Cart amount exceeds the v1 money limit");
        UUID submission=UUID.randomUUID(),event=UUID.randomUUID(); var accepted=clock.instant();
        Cart snapshot=new Cart(store,cart,version+1,"SUBMITTED",List.copyOf(lines),total.toPlainString(),"RUB",submission);
        OrderPayload payload=new OrderPayload(submission,cart,accepted,snapshot.items(),snapshot.totalAmount(),"RUB");
        String eventJson=codec.json(new OrderEvent(event,"OrderSubmitted",1,accepted,store,payload));
        jdbc.update("""
                INSERT INTO submissions(submission_id,store_id,cart_id,idempotency_key,request_fingerprint,expected_cart_version,event_id,accepted_at,cart_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,submission,store,cart,key,fingerprint,version,event,Timestamp.from(accepted),codec.json(snapshot));
        for(CartLine line:lines) {
            jdbc.update("UPDATE inventory SET available_quantity=available_quantity-? WHERE store_id=? AND stock_item_id=?",
                    line.quantity(),store,line.stockItemId());
            jdbc.update("INSERT INTO stock_expenses(store_id,submission_id,stock_item_id,quantity,unit_price,accepted_at) VALUES(?,?,?,?,?,?)",
                    store,submission,line.stockItemId(),line.quantity(),new BigDecimal(line.unitPrice()),Timestamp.from(accepted));
        }
        jdbc.update("INSERT INTO store_outbox(event_id,submission_id,store_id,payload,next_attempt_at) VALUES(?,?,?,?,?)",
                event,submission,store,eventJson,Timestamp.from(accepted));
        jdbc.update("UPDATE carts SET state='SUBMITTED',version=version+1 WHERE store_id=? AND cart_id=?",store,cart);
        return view(store,submission);
    }

    /** Rereads a concurrent UNIQUE winner after the losing transaction has rolled back; no aborted transaction is reused. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<Submission> replay(String store,String key,String fingerprint) { return existing(store,key,fingerprint); }

    /** Returns an existing store/key result, requiring the original canonical request tuple even for a closed cart. */
    private Optional<Submission> existing(String store,String key,String fingerprint) {
        List<Map<String,Object>> found=jdbc.queryForList("SELECT submission_id,request_fingerprint FROM submissions WHERE store_id=? AND idempotency_key=?",store,key);
        if(found.isEmpty()) return Optional.empty();
        if(!fingerprint.equals(found.getFirst().get("request_fingerprint")))
            throw new ShopException(409,"IDEMPOTENCY_KEY_REUSED","Idempotency-Key belongs to a different request");
        return Optional.of(view(store,(UUID)found.getFirst().get("submission_id")));
    }

    /** Reads one scoped operation and its current publication status from a single SQL snapshot; broker ack is not payment. */
    @Transactional(readOnly=true)
    public Submission view(String store,UUID id) {
        List<Submission> rows=jdbc.query("""
                SELECT s.store_id,s.submission_id,s.cart_id,s.event_id,s.accepted_at,o.publication_status,o.published_at
                FROM submissions s JOIN store_outbox o ON o.submission_id=s.submission_id
                WHERE s.store_id=? AND s.submission_id=?
                """,(row,number) -> new Submission(row.getString("store_id"),row.getObject("submission_id",UUID.class),
                row.getObject("cart_id",UUID.class),row.getObject("event_id",UUID.class),row.getString("publication_status"),
                row.getTimestamp("accepted_at").toInstant(),row.getTimestamp("published_at")==null?null:row.getTimestamp("published_at").toInstant()),store,id);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Submission not found"); return rows.getFirst();
    }

    /** Claims one due immutable event using SKIP LOCKED and a persisted lease; restart reclaims an expired owner without new expenses. */
    @Transactional
    public Optional<OutboxWork> claimOutbox() {
        Timestamp now=Timestamp.from(clock.instant());
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT event_id,store_id,payload,attempt_count FROM store_outbox WHERE publication_status='PENDING'
                AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,now,now);
        if(rows.isEmpty()) return Optional.empty(); Map<String,Object> row=rows.getFirst();
        UUID event=(UUID)row.get("event_id"),token=UUID.randomUUID();
        int attempts=(int)Math.min(Integer.MAX_VALUE,((Number)row.get("attempt_count")).longValue()+1);
        jdbc.update("UPDATE store_outbox SET lease_token=?,lease_until=?,attempt_count=? WHERE event_id=?",
                token,Timestamp.from(clock.instant().plusMillis(leaseMs)),attempts,event);
        return Optional.of(new OutboxWork(event,(String)row.get("store_id"),(String)row.get("payload"),token,attempts));
    }

    /** Marks broker acknowledgement only for the current persisted owner; a stale sender cannot overwrite a newer lease/result. */
    @Transactional
    public void published(OutboxWork work) {
        jdbc.update("""
                UPDATE store_outbox SET publication_status='PUBLISHED',published_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND publication_status='PENDING' AND lease_token=?
                """,Timestamp.from(clock.instant()),work.eventId(),work.leaseToken());
    }

    /** Retains PENDING and schedules 1/2/4/8/16/32/60-second retries; no stock/cart/submission writes occur during recovery. */
    @Transactional
    public void failedSend(OutboxWork work) {
        long delay=Math.min(60,1L<<Math.min(6,Math.max(0,work.attemptCount()-1)));
        jdbc.update("""
                UPDATE store_outbox SET next_attempt_at=?,last_error='Kafka acknowledgement unavailable',lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND publication_status='PENDING' AND lease_token=?
                """,Timestamp.from(clock.instant().plusSeconds(delay)),work.eventId(),work.leaseToken());
    }
}
