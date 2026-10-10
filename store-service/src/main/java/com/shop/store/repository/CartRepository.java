package com.shop.store.repository;

import com.shop.store.dto.CartLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Выполняет SQL для CartRepository; блокировки и записи входят в транзакцию вызывающего сервиса. */
@Repository
public class CartRepository {
    private final JdbcTemplate jdbc;

    /**
     * Получает JDBC для участия в транзакциях сервиса; конструктор не обращается к БД.
     *
     * @param jdbc JDBC-адаптер с участием в текущей транзакции Spring
     */
    public CartRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Создаёт пустую корзину магазина; повтор UUID отклоняется ограничением БД. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param cart UUID корзины
     * @param store идентификатор магазина
     * @param createdAt UTC-время создания корзины
     * @return число изменённых строк
     */
    public int insertCart(UUID cart, String store, Timestamp createdAt) {
        return jdbc.update("INSERT INTO carts(cart_id,store_id,created_at) VALUES(?,?,?)", cart, store, createdAt);
    }

    /**
     * Читает сохранённый снимок принятой корзины в транзакции вызывающего сервиса. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<String> snapshots(String store, UUID cart) {
        return jdbc.queryForList("SELECT cart_snapshot FROM submissions WHERE store_id=? AND cart_id=?", String.class, store, cart);
    }

    /**
     * Читает до 1001 позиции с текущими ценами и точными суммами; дополнительная строка выявляет превышение
     * лимита. Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<CartLine> lines(String store, UUID cart) {
        return jdbc.query("""
                SELECT i.stock_item_id,i.product_id,i.short_name,i.unit_price,c.quantity FROM cart_items c
                JOIN inventory i ON i.store_id=c.store_id AND i.stock_item_id=c.stock_item_id
                WHERE c.store_id=? AND c.cart_id=? ORDER BY i.stock_item_id LIMIT 1001
                """, (row,number) -> {
            BigDecimal price=row.getBigDecimal("unit_price"); int quantity=row.getInt("quantity");
            return new CartLine(row.getObject("stock_item_id",UUID.class),row.getString("product_id"),row.getString("short_name"),
                    quantity,price.toPlainString(),price.multiply(BigDecimal.valueOf(quantity)).toPlainString());
        }, store, cart);
    }

    /**
     * Записывает итоговое количество позиции; повтор заменяет количество без изменения остатка. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @return число изменённых строк
     */
    public int putItem(String store, UUID cart, UUID stock, int quantity) {
        return jdbc.update("""
                INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) VALUES(?,?,?,?)
                ON CONFLICT(cart_id,stock_item_id) DO UPDATE SET quantity=EXCLUDED.quantity
                """, store, cart, stock, quantity);
    }

    /**
     * Удаляет позицию указанной корзины; нулевой результат означает отсутствие позиции. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка
     * @return число изменённых строк
     */
    public int deleteItem(String store, UUID cart, UUID stock) {
        return jdbc.update("DELETE FROM cart_items WHERE store_id=? AND cart_id=? AND stock_item_id=?", store, cart, stock);
    }

    /**
     * Читает состояние и версию корзины; при lock удерживает блокировку строки до конца транзакции сервиса.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param lock нужна ли блокировка строки до конца текущей транзакции
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> headers(String store, UUID cart, boolean lock) {
        return jdbc.queryForList("SELECT version,state FROM carts WHERE store_id=? AND cart_id=?"+(lock?" FOR UPDATE":""), store, cart);
    }

    /**
     * Увеличивает версию корзины ровно один раз; откат транзакции отменяет увеличение. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return число изменённых строк
     */
    public int advance(String store, UUID cart) {
        return jdbc.update("UPDATE carts SET version=version+1 WHERE store_id=? AND cart_id=?", store, cart);
    }
}
