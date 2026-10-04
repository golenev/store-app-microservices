package com.shop.store.shop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import static com.shop.store.shop.ShopModels.*;

/** Atomically owns receipt deduplication, inventory changes and movements; failures never acknowledge Kafka prematurely. */
@Service
public class GoodsReceiver {
    private final JdbcTemplate jdbc;
    private final InventoryAccess inventory;
    private final ShopCodec codec;
    private final Clock clock;
    /** Receives short-transaction SQL, inventory locking, strict financial parsing and the UTC application clock. */
    public GoodsReceiver(JdbcTemplate jdbc,InventoryAccess inventory,ShopCodec codec,Clock clock) {
        this.jdbc=jdbc; this.inventory=inventory; this.codec=codec; this.clock=clock;
    }
    /** Saves valid receipt or diagnostic before RECORD ack; event advisory lock then store row lock fence concurrent duplicates. */
    @Transactional
    public void receive(String topic,int partition,long offset,String key,String raw) {
        Decoded decoded;
        try { decoded=codec.goods(raw); }
        catch(ShopException failure) { diagnostic(topic,partition,offset,raw,failure.code(),failure.getMessage()); return; }
        GoodsEvent event=decoded.event(); PostedPayload payload=event.payload();
        if(!event.storeId().equals(key)) { diagnostic(topic,partition,offset,raw,"VALIDATION_ERROR","Kafka key must equal storeId"); return; }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(731005,hashtext(?))",(row,number) -> 1,event.eventId().toString());
        List<String> stores=jdbc.queryForList("SELECT store_id FROM store_scopes WHERE store_id=? FOR UPDATE",String.class,event.storeId());
        if(stores.isEmpty()) { diagnostic(topic,partition,offset,raw,"NOT_FOUND","Unknown store"); return; }
        List<Map<String,Object>> seen=jdbc.queryForList("SELECT * FROM processed_events WHERE event_id=?",event.eventId());
        if(!seen.isEmpty()) {
            Map<String,Object> previous=seen.getFirst();
            if(!event.storeId().equals(previous.get("store_id")) || !payload.deliveryId().equals(previous.get("delivery_id"))
                    || !decoded.fingerprint().equals(previous.get("fingerprint")))
                diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","eventId has different business content");
            return;
        }
        List<String> prior=jdbc.queryForList("SELECT fingerprint FROM stock_receipts WHERE store_id=? AND delivery_id=?",
                String.class,event.storeId(),payload.deliveryId());
        if(!prior.isEmpty()) {
            if(!prior.getFirst().equals(decoded.fingerprint())) {
                diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","deliveryId has different business content"); return;
            }
            remember(event,decoded.fingerprint()); return;
        }
        if(jdbc.queryForObject("SELECT count(*) FROM stock_receipts WHERE store_id=? AND delivery_sequence=?",Integer.class,event.storeId(),payload.deliverySequence())!=0) {
            diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","deliverySequence belongs to another delivery"); return;
        }
        Map<String,InventoryAccess.Existing> existing=inventory.lockIncoming(event.storeId(),payload.items());
        long count=jdbc.queryForObject("SELECT count(*) FROM inventory WHERE store_id=?",Long.class,event.storeId());
        if(count+payload.items().size()-existing.size()>1000) {
            diagnostic(topic,partition,offset,raw,"VALIDATION_ERROR","Inventory would exceed the v1 catalog limit"); return;
        }
        for(PostedLine line:payload.items()) {
            InventoryAccess.Existing previous=existing.get(line.productId());
            if(previous!=null && !previous.stock().productType().equals(line.productType())) {
                diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","Product type differs from existing inventory"); return;
            }
            if(previous!=null && (long)previous.stock().availableQuantity()+line.quantity()>Integer.MAX_VALUE) {
                diagnostic(topic,partition,offset,raw,"VALIDATION_ERROR","Inventory quantity would overflow"); return;
            }
        }
        Timestamp applied=Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO stock_receipts(store_id,delivery_id,delivery_sequence,fingerprint,received_at,posted_at,applied_at,payload)
                VALUES(?,?,?,?,?,?,?,?)
                """,event.storeId(),payload.deliveryId(),payload.deliverySequence(),decoded.fingerprint(),
                Timestamp.from(payload.receivedAt()),Timestamp.from(payload.postedAt()),applied,codec.json(event));
        for(PostedLine line:payload.items()) {
            InventoryAccess.Existing previous=existing.get(line.productId());
            UUID stock=previous==null ? UUID.randomUUID() : previous.stock().stockItemId();
            if(previous==null) jdbc.update("""
                    INSERT INTO inventory(stock_item_id,store_id,product_id,product_type,short_name,description,unit_price,currency,available_quantity,last_delivery_sequence)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,stock,event.storeId(),line.productId(),line.productType(),line.shortName(),line.description(),
                    new BigDecimal(line.salePrice()),line.currency(),line.quantity(),payload.deliverySequence());
            else if(payload.deliverySequence()>previous.sequence()) jdbc.update("""
                    UPDATE inventory SET available_quantity=available_quantity+?,unit_price=?,short_name=?,description=?,last_delivery_sequence=?
                    WHERE stock_item_id=? AND store_id=?
                    """,line.quantity(),new BigDecimal(line.salePrice()),line.shortName(),line.description(),payload.deliverySequence(),stock,event.storeId());
            else jdbc.update("UPDATE inventory SET available_quantity=available_quantity+? WHERE stock_item_id=? AND store_id=?",
                    line.quantity(),stock,event.storeId());
            jdbc.update("INSERT INTO stock_movements(store_id,delivery_id,line_id,stock_item_id,quantity,unit_price,applied_at) VALUES(?,?,?,?,?,?,?)",
                    event.storeId(),payload.deliveryId(),line.lineId(),stock,line.quantity(),new BigDecimal(line.salePrice()),applied);
        }
        remember(event,decoded.fingerprint());
    }
    /** Registers another admissible transport event within the receipt transaction without creating a second movement. */
    private void remember(GoodsEvent event,String fingerprint) {
        jdbc.update("INSERT INTO processed_events(event_id,store_id,delivery_id,fingerprint) VALUES(?,?,?,?)",
                event.eventId(),event.storeId(),event.payload().deliveryId(),fingerprint);
    }
    /** Writes poison/conflict evidence once per Kafka coordinate; a failed write propagates so the consumer retries. */
    private void diagnostic(String topic,int partition,long offset,String raw,String code,String message) {
        jdbc.update("""
                INSERT INTO incoming_goods_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """,topic,partition,offset,Timestamp.from(clock.instant()),code,message,raw);
    }
}
