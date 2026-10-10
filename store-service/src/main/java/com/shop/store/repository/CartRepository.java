package com.shop.store.repository;

import com.shop.store.dto.CartLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Читает и сохраняет корзины и их позиции в PostgreSQL. Записи и блокировки участвуют в транзакции
 * вызывающего сервиса.
 */
@Repository
public class CartRepository {
    private final JdbcTemplate jdbc;

    /**
     * Подключает выполнение SQL к текущей транзакции Spring.
     *
     * @param jdbc выполнение SQL с участием в текущей транзакции Spring
     */
    public CartRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Сохраняет пустую корзину магазина. Повторный UUID отклоняется ограничением уникальности БД; запись
     * участвует в транзакции сервиса.
     *
     * @param cart UUID корзины
     * @param store идентификатор магазина
     * @param createdAt время создания корзины
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertCart(UUID cart, String store, Timestamp createdAt) {
        return jdbc.update("INSERT INTO carts(cart_id,store_id,created_at) VALUES(?,?,?)", cart, store, createdAt);
    }

    /**
     * Возвращает сохранённые JSON-снимки оформления указанной корзины. Пустой список означает, что снимка нет.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return JSON-снимки оформления корзины; пустой список, если снимка нет
     */
    public List<String> snapshots(String store, UUID cart) {
        return jdbc.queryForList("SELECT cart_snapshot FROM submissions WHERE store_id=? AND cart_id=?", String.class, store, cart);
    }

    /**
     * Читает позиции корзины с текущими ценами и вычисляет сумму каждой позиции. Возвращает не более 1001
     * строки: дополнительная строка позволяет сервису обнаружить превышение лимита в 1000 позиций.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return позиции корзины с текущими ценами и суммами; для пустой корзины список пуст
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
     * Сохраняет итоговое количество товара в корзине. Если позиция уже есть, заменяет её количество; остаток
     * товара не меняется.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка магазина
     * @param quantity новое итоговое количество товара в корзине
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int putItem(String store, UUID cart, UUID stock, int quantity) {
        return jdbc.update("""
                INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) VALUES(?,?,?,?)
                ON CONFLICT(cart_id,stock_item_id) DO UPDATE SET quantity=EXCLUDED.quantity
                """, store, cart, stock, quantity);
    }

    /**
     * Удаляет выбранную позицию корзины. Если позиции нет, возвращает 0 и ничего не меняет.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int deleteItem(String store, UUID cart, UUID stock) {
        return jdbc.update("DELETE FROM cart_items WHERE store_id=? AND cart_id=? AND stock_item_id=?", store, cart, stock);
    }

    /**
     * Читает состояние и версию корзины. При включённой блокировке другие изменения этой корзины ждут
     * завершения транзакции сервиса; пустой список означает отсутствие корзины.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param lock нужно ли заблокировать строку до завершения текущей транзакции
     * @return состояние и версия корзины; пустой список, если корзина отсутствует
     */
    public List<Map<String,Object>> headers(String store, UUID cart, boolean lock) {
        return jdbc.queryForList("SELECT version,state FROM carts WHERE store_id=? AND cart_id=?"+(lock?" FOR UPDATE":""), store, cart);
    }

    /**
     * Увеличивает версию корзины на один. Изменение сохраняется или отменяется вместе с транзакцией сервиса.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int advance(String store, UUID cart) {
        return jdbc.update("UPDATE carts SET version=version+1 WHERE store_id=? AND cart_id=?", store, cart);
    }
}
