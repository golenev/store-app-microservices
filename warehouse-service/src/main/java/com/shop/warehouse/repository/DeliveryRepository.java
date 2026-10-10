package com.shop.warehouse.repository;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Failure;
import com.shop.warehouse.dto.Line;
import com.shop.warehouse.dto.View;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Выполняет SQL для DeliveryRepository; блокировки и записи входят в транзакцию вызывающего сервиса. */
@Repository
public class DeliveryRepository {
    private final JdbcTemplate jdbc;
    private final DeliveryCodec codec;

    /**
     * Получает JDBC и средство восстановления сохранённых JSON; конструктор не обращается к БД.
     *
     * @param jdbc JDBC-адаптер с участием в текущей транзакции Spring
     * @param codec строгий разбор и сериализация протокола
     */
    public DeliveryRepository(JdbcTemplate jdbc, DeliveryCodec codec) { this.jdbc = jdbc; this.codec = codec; }

    /**
     * Сериализует приёмку транзакционной advisory-блокировкой для проверки дублей события и поставки.
     * Транзакцией управляет вызывающий сервис.
     */
    public void lockIngress() {
        jdbc.execute("SELECT pg_advisory_xact_lock(731004)");
    }

    /**
     * Проверяет существование магазина через количество строк, не создавая новые магазины. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     */
    public Integer storeCount(String store) {
        return jdbc.queryForObject("SELECT count(*) FROM stores WHERE store_id=?", Integer.class, store);
    }

    /**
     * Читает сохранённое событие для проверки идентичности повторного входа. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> receivedEvents(UUID eventId) {
        return jdbc.queryForList("SELECT * FROM received_events WHERE event_id=?", eventId);
    }

    /**
     * Читает отпечаток поставки в пределах магазина для проверки конфликтующего повтора. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<String> deliveryFingerprints(String store, String delivery) {
        return jdbc.queryForList("SELECT fingerprint FROM deliveries WHERE store_id=? AND delivery_id=?", String.class, store, delivery);
    }

    /**
     * Атомарно увеличивает порядок первой приёмки; пустой список означает исчерпание допустимого значения.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Long> nextSequence(String store) {
        return jdbc.queryForList("""
                    UPDATE stores SET delivery_sequence=delivery_sequence+1
                    WHERE store_id=? AND delivery_sequence < 9007199254740991 RETURNING delivery_sequence
                    """, Long.class, store);
    }

    /**
     * Сохраняет поставку с исходным содержимым и состоянием в общей транзакции приёмки. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param sequence неизменяемый порядок первой приёмки поставки в WAREHOUSE
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @param state сохраняемое состояние поставки
     * @param receivedAt UTC-время первой приёмки поставки
     * @param nextAttemptAt UTC-время следующей попытки
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param payload сериализованное содержимое события или проверенный объект согласно типу
     * @return число изменённых строк
     */
    public int insertDelivery(String store, String delivery, long sequence, String fingerprint, String state, Timestamp receivedAt, Timestamp nextAttemptAt, String failure, String payload) {
        return jdbc.update("""
                    INSERT INTO deliveries(store_id,delivery_id,delivery_sequence,fingerprint,state,received_at,next_attempt_at,last_error,original_payload)
                    VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """, store, delivery, sequence, fingerprint, state, receivedAt, nextAttemptAt, failure, payload);
    }

    /**
     * Сохраняет валидную строку поставки; частичная запись отменяется при откате приёмки. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param lineId идентификатор строки поставки
     * @param product идентификатор продукта
     * @param productType тип продукта FOOD или NON_FOOD
     * @param shortName короткое название продукта
     * @param description описание продукта
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param price точная денежная цена без floating point
     * @param currency валюта денежного значения
     * @return число изменённых строк
     */
    public int insertInputLine(String store, String delivery, String lineId, String product, String productType, String shortName, String description, int quantity, BigDecimal price, String currency) {
        return jdbc.update("""
                    INSERT INTO delivery_items(store_id,delivery_id,line_id,product_id,product_type,short_name,description,quantity,purchase_price,currency)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, store, delivery, lineId, product, productType, shortName, description, quantity, price, currency);
    }

    /**
     * Регистрирует входное событие вместе с поставкой; повтор UUID ограничивается БД. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @return число изменённых строк
     */
    public int rememberEvent(UUID eventId, String store, String delivery, String fingerprint) {
        return jdbc.update("INSERT INTO received_events(event_id,store_id,delivery_id,fingerprint) VALUES(?,?,?,?)", eventId, store, delivery, fingerprint);
    }

    /**
     * Записывает диагностику один раз на координаты Kafka; сбой записи распространяется потребителю.
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
                INSERT INTO delivery_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """, topic, partition, offset, recordedAt, code, message, raw);
    }

    /**
     * Читает поставку и её строки; REJECTED сохраняет исходное содержимое вместо вымышленных валидных строк.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<View> views(String store, String delivery) {
        return jdbc.query("SELECT * FROM deliveries WHERE store_id=? AND delivery_id=?", (row, number) ->
                new View(store, delivery, row.getLong("delivery_sequence"), row.getString("state"), instant(row, "received_at"),
                        instant(row, "posted_at"), row.getLong("attempt_count"), instant(row, "next_attempt_at"),
                        row.getString("last_error") == null ? null : codec.restore(row.getString("last_error"), Failure.class),
                        lines(store, delivery), "REJECTED".equals(row.getString("state")) ? codec.read(row.getString("original_payload")) : null), store, delivery);
    }

    /**
     * Читает строки в порядке lineId с точными денежными значениями и nullable-полями незавершённого расчёта.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Line> lines(String store, String delivery) {
        return jdbc.query("SELECT * FROM delivery_items WHERE store_id=? AND delivery_id=? ORDER BY line_id", (row, number) -> {
            BigDecimal rate = row.getBigDecimal("markup_rate"), sale = row.getBigDecimal("sale_price");
            return new Line(row.getString("line_id"), row.getString("product_id"), row.getString("product_type"),
                    row.getString("short_name"), row.getString("description"), row.getInt("quantity"),
                    row.getBigDecimal("purchase_price").toPlainString(), row.getString("currency"),
                    rate == null ? null : rate.toPlainString(), row.getObject("tariff_rule_id", UUID.class),
                    rate == null ? null : row.getLong("tariff_version"), sale == null ? null : sale.toPlainString());
        }, store, delivery);
    }

    /**
     * Блокирует поставку до конца диагностического повтора; отсутствующую поставку отклоняет сервис.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> lockDelivery(String store, String delivery) {
        return jdbc.queryForList("SELECT state FROM deliveries WHERE store_id=? AND delivery_id=? FOR UPDATE", store, delivery);
    }

    /**
     * Ускоряет следующую попытку, сохраняя токен уже работающего обработчика. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param nextAttemptAt UTC-время следующей попытки
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return число изменённых строк
     */
    public int schedulePricing(Timestamp nextAttemptAt, String store, String delivery) {
        return jdbc.update("UPDATE deliveries SET next_attempt_at=? WHERE store_id=? AND delivery_id=?", nextAttemptAt, store, delivery);
    }

    /**
     * Выбирает одну ожидающую поставку с истёкшим или свободным захватом через SKIP LOCKED. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param dueAt UTC-граница наступившей попытки
     * @param expiredAt UTC-граница просроченного захвата
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> lockDuePricing(Timestamp dueAt, Timestamp expiredAt) {
        return jdbc.queryForList("""
                SELECT d.store_id,d.delivery_id,s.city,d.attempt_count FROM deliveries d JOIN stores s USING(store_id)
                WHERE d.state='WAITING_PRICING' AND d.next_attempt_at<=? AND (d.lease_until IS NULL OR d.lease_until<=?)
                ORDER BY d.next_attempt_at,d.store_id,d.delivery_sequence LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """, dueAt, expiredAt);
    }

    /**
     * Записывает владельца и увеличивает число попыток до HTTP-запроса тарифов. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param token токен текущего владельца захвата
     * @param leaseUntil UTC-время окончания захвата
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return число изменённых строк
     */
    public int claimPricing(UUID token, Timestamp leaseUntil, String store, String delivery) {
        return jdbc.update("UPDATE deliveries SET lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE store_id=? AND delivery_id=?", token, leaseUntil, store, delivery);
    }

    /**
     * Продлевает захват только для текущего владельца WAITING_PRICING; ноль означает потерю владения.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param leaseUntil UTC-время окончания захвата
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param token токен текущего владельца захвата
     * @return число изменённых строк
     */
    public int renewPricing(Timestamp leaseUntil, String store, String delivery, UUID token) {
        return jdbc.update("UPDATE deliveries SET lease_until=? WHERE store_id=? AND delivery_id=? AND lease_token=? AND state='WAITING_PRICING'", leaseUntil, store, delivery, token);
    }

    /**
     * Блокирует поставку и читает токен перед фиксацией результата расчёта. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<String> lockPricingToken(String store, String delivery) {
        return jdbc.queryForList("SELECT lease_token::text FROM deliveries WHERE store_id=? AND delivery_id=? FOR UPDATE", String.class, store, delivery);
    }

    /**
     * Сохраняет расчёт строки внутри общей транзакции оприходования; частичный результат не фиксируется.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param rate дробная наценка
     * @param rule UUID тарифного правила
     * @param version версия правила или корзины согласно операции
     * @param price точная денежная цена без floating point
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param lineId идентификатор строки поставки
     * @return число изменённых строк
     */
    public int updatePricedLine(BigDecimal rate, UUID rule, long version, BigDecimal price, String store, String delivery, String lineId) {
        return jdbc.update("""
                UPDATE delivery_items SET markup_rate=?,tariff_rule_id=?,tariff_version=?,sale_price=?
                WHERE store_id=? AND delivery_id=? AND line_id=?
                """, rate, rule, version, price, store, delivery, lineId);
    }

    /**
     * Сохраняет единственное неизменяемое GoodsPosted в общей транзакции с POSTED. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param payload сериализованное содержимое события или проверенный объект согласно типу
     * @param nextAttemptAt UTC-время следующей попытки
     * @return число изменённых строк
     */
    public int insertOutbox(UUID eventId, String store, String delivery, String payload, Timestamp nextAttemptAt) {
        return jdbc.update("INSERT INTO warehouse_outbox(event_id,store_id,delivery_id,payload,next_attempt_at) VALUES(?,?,?,?,?)", eventId, store, delivery, payload, nextAttemptAt);
    }

    /**
     * Переводит поставку в POSTED и освобождает захват вместе с записью outbox. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param postedAt UTC-время завершения расчёта поставки
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @return число изменённых строк
     */
    public int markPosted(Timestamp postedAt, String store, String delivery) {
        return jdbc.update("""
                UPDATE deliveries SET state='POSTED',posted_at=?,next_attempt_at=NULL,last_error=NULL,lease_token=NULL,lease_until=NULL
                WHERE store_id=? AND delivery_id=?
                """, postedAt, store, delivery);
    }

    /**
     * Сохраняет ошибку и повтор только для текущего владельца WAITING_PRICING. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param nextAttemptAt UTC-время следующей попытки
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     * @param token токен текущего владельца захвата
     * @return число изменённых строк
     */
    public int schedulePricingRetry(String failure, Timestamp nextAttemptAt, String store, String delivery, UUID token) {
        return jdbc.update("""
                UPDATE deliveries SET last_error=?::jsonb,next_attempt_at=?,lease_token=NULL,lease_until=NULL
                WHERE store_id=? AND delivery_id=? AND lease_token=? AND state='WAITING_PRICING'
                """, failure, nextAttemptAt, store, delivery, token);
    }

    /**
     * Выбирает наступившее PENDING-событие с SKIP LOCKED, восстанавливая просроченный захват. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param dueAt UTC-граница наступившей попытки
     * @param expiredAt UTC-граница просроченного захвата
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> lockDueOutbox(Timestamp dueAt, Timestamp expiredAt) {
        return jdbc.queryForList("""
                SELECT event_id,store_id,payload,attempt_count FROM warehouse_outbox
                WHERE publication_status='PENDING' AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, dueAt, expiredAt);
    }

    /**
     * Фиксирует владельца и число попыток перед публикацией события за пределами SQL-транзакции. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param token токен текущего владельца захвата
     * @param leaseUntil UTC-время окончания захвата
     * @param eventId идентификатор события для защиты повторов
     * @return число изменённых строк
     */
    public int claimOutbox(UUID token, Timestamp leaseUntil, UUID eventId) {
        return jdbc.update("UPDATE warehouse_outbox SET lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE event_id=?", token, leaseUntil, eventId);
    }

    /**
     * Записывает подтверждение брокера только для текущего владельца PENDING-события. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param publishedAt UTC-время подтверждения публикации
     * @param eventId идентификатор события для защиты повторов
     * @param token токен текущего владельца захвата
     * @return число изменённых строк
     */
    public int markPublished(Timestamp publishedAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE warehouse_outbox SET publication_status='PUBLISHED',published_at=?,last_error=NULL,lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND lease_token=? AND publication_status='PENDING'
                """, publishedAt, eventId, token);
    }

    /**
     * Сохраняет ожидание повторной публикации того же события, освобождая захват текущего владельца.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param nextAttemptAt UTC-время следующей попытки
     * @param eventId идентификатор события для защиты повторов
     * @param token токен текущего владельца захвата
     * @return число изменённых строк
     */
    public int scheduleSendRetry(Timestamp nextAttemptAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE warehouse_outbox SET last_error='Kafka publication unavailable',next_attempt_at=?,lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND lease_token=? AND publication_status='PENDING'
                """, nextAttemptAt, eventId, token);
    }
    /**
     * Преобразует nullable-время строки БД в UTC; отсутствие значения сохраняет null.
     *
     * @param row строка результата JDBC
     * @param field имя читаемого поля
     */
    private Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
