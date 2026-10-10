package com.shop.store.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Сохраняет приходы, движения и отметки об обработанных событиях в PostgreSQL. Все записи и блокировки
 * входят в транзакцию сервиса приёмки.
 */
@Repository
public class GoodsReceiptRepository {
    private final JdbcTemplate jdbc;

    /**
     * Подключает выполнение SQL к текущей транзакции Spring.
     *
     * @param jdbc выполнение SQL с участием в текущей транзакции Spring
     */
    public GoodsReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Блокирует обработку данного идентификатора события до конца транзакции. Одновременный повтор ждёт её
     * завершения и затем может проверить сохранённый результат.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return значение 1 после получения блокировки события
     */
    public Integer lockEvent(String eventId) {
        return jdbc.queryForObject("SELECT pg_advisory_xact_lock(731005,hashtext(?))", (row,number) -> 1, eventId);
    }

    /**
     * Блокирует строку магазина до конца транзакции, чтобы его поставки применялись последовательно. Пустой
     * список означает неизвестный магазин.
     *
     * @param store идентификатор магазина
     * @return идентификатор найденного магазина или пустой список для неизвестного магазина
     */
    public List<String> lockStore(String store) {
        return jdbc.queryForList("SELECT store_id FROM store_scopes WHERE store_id=? FOR UPDATE", String.class, store);
    }

    /**
     * Возвращает сохранённые магазин, поставку и контрольную сумму обработанного события. Пустой список
     * означает, что идентификатор события ещё не зарегистрирован.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return сохранённые сведения об обработанном событии или пустой список
     */
    public List<Map<String,Object>> processedEvents(UUID eventId) {
        return jdbc.queryForList("SELECT * FROM processed_events WHERE event_id=?", eventId);
    }

    /**
     * Возвращает контрольную сумму уже принятой поставки этого магазина. По ней сервис проверяет, совпадает ли
     * содержимое повторного сообщения; пустой список означает отсутствие поставки.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return контрольная сумма принятой поставки или пустой список, если её нет
     */
    public List<String> receiptFingerprints(String store, String delivery) {
        return jdbc.queryForList("SELECT fingerprint FROM stock_receipts WHERE store_id=? AND delivery_id=?", String.class, store, delivery);
    }

    /**
     * Считает поставки магазина с заданным порядковым номером первой приёмки. Ненулевой результат позволяет
     * сервису обнаружить повторное использование номера другой поставкой.
     *
     * @param store идентификатор магазина
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @return число поставок магазина с указанным порядковым номером
     */
    public Integer sequenceCount(String store, long sequence) {
        return jdbc.queryForObject("SELECT count(*) FROM stock_receipts WHERE store_id=? AND delivery_sequence=?", Integer.class, store, sequence);
    }

    /**
     * Возвращает количество разных товаров в остатках магазина для проверки лимита каталога.
     *
     * @param store идентификатор магазина
     * @return число разных товаров в остатках магазина
     */
    public Long inventoryCount(String store) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory WHERE store_id=?", Long.class, store);
    }

    /**
     * Сохраняет принятую поставку, её контрольную сумму, даты и исходное событие. Запись выполняется вместе с
     * изменением остатков в транзакции сервиса.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
     * @param receivedAt время первой приёмки поставки в WAREHOUSE
     * @param postedAt время завершения расчёта и оприходования поставки в WAREHOUSE
     * @param appliedAt время применения прихода к остаткам магазина
     * @param payload JSON принятого события поставки для хранения истории прихода
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertReceipt(String store, String delivery, long sequence, String fingerprint, Timestamp receivedAt, Timestamp postedAt, Timestamp appliedAt, String payload) {
        return jdbc.update("""
                INSERT INTO stock_receipts(store_id,delivery_id,delivery_sequence,fingerprint,received_at,posted_at,applied_at,payload)
                VALUES(?,?,?,?,?,?,?,?)
                """, store, delivery, sequence, fingerprint, receivedAt, postedAt, appliedAt, payload);
    }

    /**
     * Создаёт остаток для первого прихода продукта в магазин. БД запрещает вторую позицию с той же парой
     * магазина и продукта.
     *
     * @param stock UUID позиции остатка магазина
     * @param store идентификатор магазина
     * @param product идентификатор продукта
     * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
     * @param shortName краткое название товара
     * @param description описание товара
     * @param price продажная цена как точное десятичное число
     * @param currency код валюты; в текущем контракте разрешён {@code RUB}
     * @param quantity количество единиц товара в этом приходе
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertStock(UUID stock, String store, String product, String productType, String shortName, String description, BigDecimal price, String currency, int quantity, long sequence) {
        return jdbc.update("""
                    INSERT INTO inventory(stock_item_id,store_id,product_id,product_type,short_name,description,unit_price,currency,available_quantity,last_delivery_sequence)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, stock, store, product, productType, shortName, description, price, currency, quantity, sequence);
    }

    /**
     * Увеличивает остаток и заменяет цену, название, описание и номер поставки, определившей цену. Сервис
     * должен заранее проверить, что эта поставка новее прежней; все изменения входят в его транзакцию.
     *
     * @param quantity количество единиц товара в этом приходе
     * @param price продажная цена как точное десятичное число
     * @param shortName краткое название товара
     * @param description описание товара
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @param stock UUID позиции остатка магазина
     * @param store идентификатор магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int addStockAndReprice(int quantity, BigDecimal price, String shortName, String description, long sequence, UUID stock, String store) {
        return jdbc.update("""
                    UPDATE inventory SET available_quantity=available_quantity+?,unit_price=?,short_name=?,description=?,last_delivery_sequence=?
                    WHERE stock_item_id=? AND store_id=?
                    """, quantity, price, shortName, description, sequence, stock, store);
    }

    /**
     * Увеличивает остаток по более старой поставке. Сохраняет цену и описание, которые уже задала более новая
     * поставка.
     *
     * @param quantity количество единиц товара в этом приходе
     * @param stock UUID позиции остатка магазина
     * @param store идентификатор магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int addStock(int quantity, UUID stock, String store) {
        return jdbc.update("UPDATE inventory SET available_quantity=available_quantity+? WHERE stock_item_id=? AND store_id=?", quantity, stock, store);
    }

    /**
     * Сохраняет движение прихода по строке поставки. Движение и изменение остатка сохраняются или отменяются
     * вместе.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param lineId идентификатор строки внутри поставки
     * @param stock UUID позиции остатка магазина
     * @param quantity количество единиц товара в этом приходе
     * @param price продажная цена как точное десятичное число
     * @param appliedAt время применения прихода к остаткам магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertMovement(String store, String delivery, String lineId, UUID stock, int quantity, BigDecimal price, Timestamp appliedAt) {
        return jdbc.update("INSERT INTO stock_movements(store_id,delivery_id,line_id,stock_item_id,quantity,unit_price,applied_at) VALUES(?,?,?,?,?,?,?)", store, delivery, lineId, stock, quantity, price, appliedAt);
    }

    /**
     * Сохраняет отметку об обработанном событии. БД запрещает повторную регистрацию того же идентификатора.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int rememberEvent(UUID eventId, String store, String delivery, String fingerprint) {
        return jdbc.update("INSERT INTO processed_events(event_id,store_id,delivery_id,fingerprint) VALUES(?,?,?,?)", eventId, store, delivery, fingerprint);
    }

    /**
     * Сохраняет сообщение и причину отказа по его координатам в Kafka. Повтор этих координат не создаёт новую
     * запись; ошибка БД требует повторной обработки сообщения.
     *
     * @param topic имя канала Kafka, из которого получено сообщение
     * @param partition номер раздела канала Kafka
     * @param offset позиция сообщения внутри раздела Kafka
     * @param recordedAt время сохранения сообщения для диагностики
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @param raw исходный текст JSON-сообщения
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertDiagnostic(String topic, int partition, long offset, Timestamp recordedAt, String code, String message, String raw) {
        return jdbc.update("""
                INSERT INTO incoming_goods_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """, topic, partition, offset, recordedAt, code, message, raw);
    }
}
