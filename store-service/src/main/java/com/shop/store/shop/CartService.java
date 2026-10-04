package com.shop.store.shop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import static com.shop.store.shop.ShopModels.*;

/** Independent versioned cart transactions; quantity is validated against inventory but nothing is reserved or deducted. */
@Service
public class CartService {
    private final JdbcTemplate jdbc;
    private final InventoryAccess inventory;
    private final Clock clock;
    /** Receives scoped SQL access and an injectable UTC clock; no external HTTP dependencies remain in STORE. */
    public CartService(JdbcTemplate jdbc,InventoryAccess inventory,Clock clock) { this.jdbc=jdbc; this.inventory=inventory; this.clock=clock; }
    /** Reads a bounded store-scoped catalog, retaining zero-stock SKUs and stable identifiers. */
    @Transactional(readOnly=true)
    public Catalog catalog(String store) { return inventory.catalog(store); }
    /** Creates a fresh empty OPEN cart at version zero; UUID scopes independent browser contexts or parallel tests. */
    @Transactional
    public Cart create(String store) {
        inventory.requireStore(store); UUID cart=UUID.randomUUID();
        jdbc.update("INSERT INTO carts(cart_id,store_id,created_at) VALUES(?,?,?)",cart,store,Timestamp.from(clock.instant()));
        return get(store,cart);
    }
    /** Returns header/composition/current prices from one repeatable snapshot, avoiding mixed versions during concurrent edits. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Cart get(String store,UUID cart) {
        Map<String,Object> header=header(store,cart,false);
        if(!header.get("state").equals("OPEN"))
            throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart snapshot unavailable");
        List<CartLine> lines=jdbc.query("""
                SELECT i.stock_item_id,i.product_id,i.short_name,i.unit_price,c.quantity FROM cart_items c
                JOIN inventory i ON i.store_id=c.store_id AND i.stock_item_id=c.stock_item_id
                WHERE c.store_id=? AND c.cart_id=? ORDER BY i.stock_item_id LIMIT 1001
                """,(row,number) -> {
            BigDecimal price=row.getBigDecimal("unit_price"); int quantity=row.getInt("quantity");
            return new CartLine(row.getObject("stock_item_id",UUID.class),row.getString("product_id"),row.getString("short_name"),
                    quantity,price.toPlainString(),price.multiply(BigDecimal.valueOf(quantity)).toPlainString());
        },store,cart);
        BigDecimal total=lines.stream().map(line -> new BigDecimal(line.lineTotal())).reduce(new BigDecimal("0.00"),BigDecimal::add);
        if(lines.size()>1000 || !total.toPlainString().matches("(0|[1-9][0-9]{0,35})\\.[0-9]{2}"))
            throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart amount or size exceeds the v1 response limit");
        return new Cart(store,cart,((Number)header.get("version")).longValue(),"OPEN",lines,total.toPlainString(),"RUB");
    }
    /** Locks cart then stock; checks expected version/current quantity and replaces one line atomically, never touching inventory quantity. */
    @Transactional
    public Cart put(String store,UUID cart,UUID stock,PutItem input) {
        if(input==null || input.quantity()<1 || input.expectedCartVersion()<0 || input.expectedCartVersion()>ShopCodec.MAX_VERSION)
            throw new ShopException(400,"VALIDATION_ERROR","Invalid cart quantity or version");
        editable(header(store,cart,true),input.expectedCartVersion());
        Stock current=inventory.lockStock(store,stock);
        if(input.quantity()>current.availableQuantity()) throw new ShopException(409,"INSUFFICIENT_STOCK","Requested quantity exceeds current stock");
        jdbc.update("""
                INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) VALUES(?,?,?,?)
                ON CONFLICT(cart_id,stock_item_id) DO UPDATE SET quantity=EXCLUDED.quantity
                """,store,cart,stock,input.quantity());
        advance(store,cart);
        return mutationView(store,cart);
    }
    /** Removes a present scope-owned line under the cart/version lock; missing or stale requests leave composition/version unchanged. */
    @Transactional
    public Cart delete(String store,UUID cart,UUID stock,long expected) {
        editable(header(store,cart,true),expected);
        if(jdbc.update("DELETE FROM cart_items WHERE store_id=? AND cart_id=? AND stock_item_id=?",store,cart,stock)==0)
            throw new ShopException(404,"NOT_FOUND","Cart item not found");
        advance(store,cart); return mutationView(store,cart);
    }
    /** Reads only a store-owned cart, optionally locking it before a mutation or future submit. */
    private Map<String,Object> header(String store,UUID cart,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT version,state FROM carts WHERE store_id=? AND cart_id=?"+(lock?" FOR UPDATE":""),store,cart);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Cart not found"); return rows.getFirst();
    }
    /** Requires OPEN and exact expected version; exhausted versions fail before any write. */
    private void editable(Map<String,Object> header,long expected) {
        if(expected<0 || expected>ShopCodec.MAX_VERSION) throw new ShopException(400,"VALIDATION_ERROR","Invalid expectedCartVersion");
        if(!header.get("state").equals("OPEN")) throw new ShopException(409,"CART_ALREADY_SUBMITTED","Cart is already submitted");
        long version=((Number)header.get("version")).longValue();
        if(version!=expected) throw new ShopException(409,"CART_VERSION_CONFLICT","Cart version has changed");
        if(version==ShopCodec.MAX_VERSION) throw new ShopException(400,"VALIDATION_ERROR","Cart version is exhausted");
    }
    /** Increments once even when PUT repeats the same quantity; transaction rollback preserves version on failure. */
    private void advance(String store,UUID cart) { jdbc.update("UPDATE carts SET version=version+1 WHERE store_id=? AND cart_id=?",store,cart); }
    /** Builds the changed view inside its transaction; an unrepresentable amount rejects and rolls back the entire mutation. */
    private Cart mutationView(String store,UUID cart) {
        try { return get(store,cart); }
        catch(ShopException failure) {
            if(failure.status()==503) throw new ShopException(400,"VALIDATION_ERROR","Cart amount exceeds the v1 money limit");
            throw failure;
        }
    }
}
