package com.shop.store.repository;

import com.shop.store.dto.Catalog;
import com.shop.store.dto.Stock;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.model.ExistingStock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

/** Читает и блокирует остатки через JDBC в транзакции вызывающего сервиса. */
@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;
    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param jdbc JDBC-адаптер с участием в текущей транзакции Spring
     */
    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /**
     * Проверяет существование магазина store; отсутствие вызывает NOT_FOUND без создания новых fixtures.
     *
     * @param store идентификатор магазина
     */
    public void requireStore(String store) {
        if(jdbc.queryForObject("SELECT count(*) FROM store_scopes WHERE store_id=?",Integer.class,store)==0)
            throw new ShopException(404,"NOT_FOUND","Store not found");
    }
    /**
     * Возвращает позиции магазина store, включая нулевое количество. Дополнительная строка выявляет превышение
     * 1000 позиций и вызывает DEPENDENCY_UNAVAILABLE.
     *
     * @param store идентификатор магазина
     */
    public Catalog catalog(String store) {
        requireStore(store);
        List<Stock> stocks=jdbc.query("SELECT * FROM inventory WHERE store_id=? ORDER BY product_id LIMIT 1001",this::stock,store);
        if(stocks.size()>1000) throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Inventory exceeds the v1 catalog limit");
        return new Catalog(store,stocks);
    }
    /**
     * Блокирует существующие позиции lines магазина store в порядке UUID до конца транзакции сервиса и
     * возвращает их по productId.
     *
     * @param store идентификатор магазина
     * @param lines строки поставки для блокировки затронутых остатков
     */
    public Map<String,ExistingStock> lockIncoming(String store,List<PostedLine> lines) {
        String placeholders=String.join(",",Collections.nCopies(lines.size(),"?"));
        List<Object> params=new ArrayList<>(); params.add(store); lines.forEach(line -> params.add(line.productId()));
        List<ExistingStock> rows=jdbc.query("SELECT * FROM inventory WHERE store_id=? AND product_id IN ("+placeholders+") ORDER BY stock_item_id FOR UPDATE",
                (row,number) -> new ExistingStock(stock(row,number),row.getLong("last_delivery_sequence")),params.toArray());
        Map<String,ExistingStock> result=new HashMap<>(); rows.forEach(row -> result.put(row.stock().productId(),row)); return result;
    }
    /**
     * Блокирует позицию stock магазина store до конца транзакции; отсутствие вызывает NOT_FOUND. Блокировка не
     * резервирует товар.
     *
     * @param store идентификатор магазина
     * @param stock UUID позиции остатка
     */
    public Stock lockStock(String store,UUID stock) {
        List<Stock> rows=jdbc.query("SELECT * FROM inventory WHERE store_id=? AND stock_item_id=? FOR UPDATE",this::stock,store,stock);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Stock item not found"); return rows.getFirst();
    }
    /**
     * Преобразует row в позицию каталога с точными decimal-строками и UUID; ошибка чтения распространяется как
     * SQLException.
     *
     * @param row строка результата JDBC
     * @param number номер строки JDBC, не влияющий на отображение
     */
    private Stock stock(ResultSet row,int number) throws SQLException {
        return new Stock(row.getObject("stock_item_id",UUID.class),row.getString("product_id"),row.getString("product_type"),
                row.getString("short_name"),row.getString("description"),row.getBigDecimal("unit_price").toPlainString(),
                row.getString("currency"),row.getInt("available_quantity"));
    }
}
