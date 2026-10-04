package com.shop.store.shop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;
import static com.shop.store.shop.ShopModels.*;

/** Transaction-aware stock SQL shared by receipt application and carts; callers own transaction boundaries. */
@Repository
public class InventoryAccess {
    private final JdbcTemplate jdbc;
    /** Receives the restricted STORE datasource through Spring's transaction-aware adapter. */
    public InventoryAccess(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Existing(Stock stock,long sequence) { }
    /** Rejects unknown store scopes without leaking or inventing another service's fixtures. */
    public void requireStore(String store) {
        if(jdbc.queryForObject("SELECT count(*) FROM store_scopes WHERE store_id=?",Integer.class,store)==0)
            throw new ShopException(404,"NOT_FOUND","Store not found");
    }
    /** Returns every SKU including zero quantity; overflow sentinel prevents silent contract truncation. */
    public Catalog catalog(String store) {
        requireStore(store);
        List<Stock> stocks=jdbc.query("SELECT * FROM inventory WHERE store_id=? ORDER BY product_id LIMIT 1001",this::stock,store);
        if(stocks.size()>1000) throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Inventory exceeds the v1 catalog limit");
        return new Catalog(store,stocks);
    }
    /** Locks existing affected stock rows in UUID order, matching acceptance's stable inventory lock order. */
    public Map<String,Existing> lockIncoming(String store,List<PostedLine> lines) {
        String placeholders=String.join(",",Collections.nCopies(lines.size(),"?"));
        List<Object> params=new ArrayList<>(); params.add(store); lines.forEach(line -> params.add(line.productId()));
        List<Existing> rows=jdbc.query("SELECT * FROM inventory WHERE store_id=? AND product_id IN ("+placeholders+") ORDER BY stock_item_id FOR UPDATE",
                (row,number) -> new Existing(stock(row,number),row.getLong("last_delivery_sequence")),params.toArray());
        Map<String,Existing> result=new HashMap<>(); rows.forEach(row -> result.put(row.stock().productId(),row)); return result;
    }
    /** Locks one scope-owned SKU so quantity validation cannot race acceptance; this short lock does not reserve goods. */
    public Stock lockStock(String store,UUID stock) {
        List<Stock> rows=jdbc.query("SELECT * FROM inventory WHERE store_id=? AND stock_item_id=? FOR UPDATE",this::stock,store,stock);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Stock item not found"); return rows.getFirst();
    }
    /** Maps exact SQL decimals and UUIDs to catalog wire fields, with no double conversion. */
    private Stock stock(ResultSet row,int number) throws SQLException {
        return new Stock(row.getObject("stock_item_id",UUID.class),row.getString("product_id"),row.getString("product_type"),
                row.getString("short_name"),row.getString("description"),row.getBigDecimal("unit_price").toPlainString(),
                row.getString("currency"),row.getInt("available_quantity"));
    }
}
