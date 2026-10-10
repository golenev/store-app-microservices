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

/**
 * Читает каталог магазина и блокирует остатки в PostgreSQL. Блокировки действуют до завершения транзакции
 * вызывающего сервиса.
 */
@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;
    /**
     * Подключает выполнение SQL к текущей транзакции Spring.
     *
     * @param jdbc выполнение SQL с участием в текущей транзакции Spring
     */
    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /**
     * Проверяет, что магазин существует. Для неизвестного магазина выдаёт {@code NOT_FOUND}; новые магазины не
     * создаёт.
     *
     * @param store идентификатор магазина
     */
    public void requireStore(String store) {
        if(jdbc.queryForObject("SELECT count(*) FROM store_scopes WHERE store_id=?",Integer.class,store)==0)
            throw new ShopException(404,"NOT_FOUND","Store not found");
    }
    /**
     * Возвращает каталог магазина с ценами и доступным количеством, включая нулевые остатки. Неизвестный
     * магазин вызывает {@code NOT_FOUND}, более 1000 позиций — {@code DEPENDENCY_UNAVAILABLE}, поскольку ответ
     * превышает лимит API.
     *
     * @param store идентификатор магазина
     * @return каталог указанного магазина с текущими ценами и остатками
     */
    public Catalog catalog(String store) {
        requireStore(store);
        List<Stock> stocks=jdbc.query("SELECT * FROM inventory WHERE store_id=? ORDER BY product_id LIMIT 1001",this::stock,store);
        if(stocks.size()>1000) throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Inventory exceeds the v1 catalog limit");
        return new Catalog(store,stocks);
    }
    /**
     * Блокирует уже существующие остатки товаров входящей поставки в порядке UUID. Возвращает их по
     * идентификатору продукта для выбора новой цены и проверки количества; блокировки держит до конца
     * транзакции сервиса.
     *
     * @param store идентификатор магазина
     * @param lines проверенные строки оприходованной поставки
     * @return существующие остатки по идентификатору продукта; новые продукты в карту не входят
     */
    public Map<String,ExistingStock> lockIncoming(String store,List<PostedLine> lines) {
        String placeholders=String.join(",",Collections.nCopies(lines.size(),"?"));
        List<Object> params=new ArrayList<>(); params.add(store); lines.forEach(line -> params.add(line.productId()));
        List<ExistingStock> rows=jdbc.query("SELECT * FROM inventory WHERE store_id=? AND product_id IN ("+placeholders+") ORDER BY stock_item_id FOR UPDATE",
                (row,number) -> new ExistingStock(stock(row,number),row.getLong("last_delivery_sequence")),params.toArray());
        Map<String,ExistingStock> result=new HashMap<>(); rows.forEach(row -> result.put(row.stock().productId(),row)); return result;
    }
    /**
     * Находит и блокирует позицию остатка указанного магазина. Отсутствие вызывает {@code NOT_FOUND};
     * блокировка действует до конца транзакции и сама по себе не резервирует товар.
     *
     * @param store идентификатор магазина
     * @param stock UUID позиции остатка магазина
     * @return найденная позиция остатка магазина
     */
    public Stock lockStock(String store,UUID stock) {
        List<Stock> rows=jdbc.query("SELECT * FROM inventory WHERE store_id=? AND stock_item_id=? FOR UPDATE",this::stock,store,stock);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Stock item not found"); return rows.getFirst();
    }
    /**
     * Преобразует строку SQL-результата в позицию каталога с UUID и точной строковой ценой. Ошибку чтения
     * колонки передаёт как {@code SQLException}.
     *
     * @param row текущая строка результата SQL-запроса
     * @param number номер строки SQL-результата; на преобразование не влияет
     * @return найденная позиция остатка магазина
     */
    private Stock stock(ResultSet row,int number) throws SQLException {
        return new Stock(row.getObject("stock_item_id",UUID.class),row.getString("product_id"),row.getString("product_type"),
                row.getString("short_name"),row.getString("description"),row.getBigDecimal("unit_price").toPlainString(),
                row.getString("currency"),row.getInt("available_quantity"));
    }
}
