package com.shop.store.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Выполняет SQL для GoodsReceiptRepository; блокировки и записи входят в транзакцию вызывающего сервиса. */
@Repository
public class GoodsReceiptRepository {
    private final JdbcTemplate jdbc;

    /**
     * Получает JDBC для участия в транзакциях сервиса; конструктор не обращается к БД.
     *
     * @param jdbc JDBC-адаптер с участием в текущей транзакции Spring
     */
    public GoodsReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Сериализует обработку одного eventId транзакционной advisory-блокировкой. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     */
    public Integer lockEvent(String eventId) {
        return jdbc.queryForObject("SELECT pg_advisory_xact_lock(731005,hashtext(?))", (row,number) -> 1, eventId);
    }

    /**
     * Блокирует существующий магазин до конца приёмки; пустой список означает неизвестный магазин. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<String> lockStore(String store) {
        return jdbc.queryForList("SELECT store_id FROM store_scopes WHERE store_id=? FOR UPDATE", String.class, store);
    }

    /**
     * Читает ранее обработанное событие для проверки повторного содержимого. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> processedEvents(UUID eventId) {
        return jdbc.queryForList("SELECT * FROM processed_events WHERE event_id=?", eventId);
    }

    /**
     * Читает отпечаток поставки магазина для защиты от повторного прихода. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<String> receiptFingerprints(String store, String delivery) {
        return jdbc.queryForList("SELECT fingerprint FROM stock_receipts WHERE store_id=? AND delivery_id=?", String.class, store, delivery);
    }

    /**
     * Считает поставки с заданным порядком приёмки; ненулевой результат выявляет занятый порядок. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param sequence неизменяемый порядок первой приёмки поставки в WAREHOUSE
     */
    public Integer sequenceCount(String store, long sequence) {
        return jdbc.queryForObject("SELECT count(*) FROM stock_receipts WHERE store_id=? AND delivery_sequence=?", Integer.class, store, sequence);
    }

    /**
     * Считает позиции магазина в текущей транзакции для проверки лимита каталога. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     */
    public Long inventoryCount(String store) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory WHERE store_id=?", Long.class, store);
    }

    /**
     * Сохраняет уникальную поставку и исходное событие; ошибка отменяет всю транзакцию приёмки. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param sequence неизменяемый порядок первой приёмки поставки в WAREHOUSE
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @param receivedAt UTC-время первой приёмки поставки
     * @param postedAt UTC-время завершения расчёта поставки
     * @param appliedAt UTC-время применения прихода к остаткам
     * @param payload сериализованное содержимое события или проверенный объект согласно типу
     * @return число изменённых строк
     */
    public int insertReceipt(String store, String delivery, long sequence, String fingerprint, Timestamp receivedAt, Timestamp postedAt, Timestamp appliedAt, String payload) {
        return jdbc.update("""
                INSERT INTO stock_receipts(store_id,delivery_id,delivery_sequence,fingerprint,received_at,posted_at,applied_at,payload)
                VALUES(?,?,?,?,?,?,?,?)
                """, store, delivery, sequence, fingerprint, receivedAt, postedAt, appliedAt, payload);
    }

    /**
     * Создаёт первую позицию продукта; уникальность пары магазин/продукт обеспечивает БД. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param stock UUID позиции остатка
     * @param store идентификатор магазина
     * @param product идентификатор продукта
     * @param productType тип продукта FOOD или NON_FOOD
     * @param shortName короткое название продукта
     * @param description описание продукта
     * @param price точная денежная цена без floating point
     * @param currency валюта денежного значения
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param sequence неизменяемый порядок первой приёмки поставки в WAREHOUSE
     * @return число изменённых строк
     */
    public int insertStock(UUID stock, String store, String product, String productType, String shortName, String description, BigDecimal price, String currency, int quantity, long sequence) {
        return jdbc.update("""
                    INSERT INTO inventory(stock_item_id,store_id,product_id,product_type,short_name,description,unit_price,currency,available_quantity,last_delivery_sequence)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, stock, store, product, productType, shortName, description, price, currency, quantity, sequence);
    }

    /**
     * Добавляет количество и обновляет цену с описанием после проверки более нового порядка поставки в сервисе.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param price точная денежная цена без floating point
     * @param shortName короткое название продукта
     * @param description описание продукта
     * @param sequence неизменяемый порядок первой приёмки поставки в WAREHOUSE
     * @param stock UUID позиции остатка
     * @param store идентификатор магазина
     * @return число изменённых строк
     */
    public int addStockAndReprice(int quantity, BigDecimal price, String shortName, String description, long sequence, UUID stock, String store) {
        return jdbc.update("""
                    UPDATE inventory SET available_quantity=available_quantity+?,unit_price=?,short_name=?,description=?,last_delivery_sequence=?
                    WHERE stock_item_id=? AND store_id=?
                    """, quantity, price, shortName, description, sequence, stock, store);
    }

    /**
     * Добавляет количество старой поставки, сохраняя более новую цену и описание. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param stock UUID позиции остатка
     * @param store идентификатор магазина
     * @return число изменённых строк
     */
    public int addStock(int quantity, UUID stock, String store) {
        return jdbc.update("UPDATE inventory SET available_quantity=available_quantity+? WHERE stock_item_id=? AND store_id=?", quantity, stock, store);
    }

    /**
     * Сохраняет движение прихода в общей транзакции с остатком и поставкой. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param lineId идентификатор строки поставки
     * @param stock UUID позиции остатка
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param price точная денежная цена без floating point
     * @param appliedAt UTC-время применения прихода к остаткам
     * @return число изменённых строк
     */
    public int insertMovement(String store, String delivery, String lineId, UUID stock, int quantity, BigDecimal price, Timestamp appliedAt) {
        return jdbc.update("INSERT INTO stock_movements(store_id,delivery_id,line_id,stock_item_id,quantity,unit_price,applied_at) VALUES(?,?,?,?,?,?,?)", store, delivery, lineId, stock, quantity, price, appliedAt);
    }

    /**
     * Регистрирует обработанный eventId; ограничение уникальности не допускает повторной регистрации.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @return число изменённых строк
     */
    public int rememberEvent(UUID eventId, String store, String delivery, String fingerprint) {
        return jdbc.update("INSERT INTO processed_events(event_id,store_id,delivery_id,fingerprint) VALUES(?,?,?,?)", eventId, store, delivery, fingerprint);
    }

    /**
     * Сохраняет диагностику один раз на координаты Kafka; ошибка записи требует повторной обработки сообщения.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param topic имя топика входного сообщения
     * @param partition номер раздела Kafka
     * @param offset смещение сообщения Kafka
     * @param recordedAt UTC-время записи диагностики
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @return число изменённых строк
     */
    public int insertDiagnostic(String topic, int partition, long offset, Timestamp recordedAt, String code, String message, String raw) {
        return jdbc.update("""
                INSERT INTO incoming_goods_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """, topic, partition, offset, recordedAt, code, message, raw);
    }
}
