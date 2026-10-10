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

/**
 * Читает и сохраняет поставки, их строки и очередь исходящих событий в PostgreSQL. Все записи и блокировки
 * входят в транзакцию вызывающего сервиса.
 */
@Repository
public class DeliveryRepository {
    private final JdbcTemplate jdbc;
    private final DeliveryCodec codec;

    /**
     * Подключает выполнение SQL и восстановление сохранённых JSON-данных поставки.
     *
     * @param jdbc выполнение SQL с участием в текущей транзакции Spring
     * @param codec проверка событий поставок и преобразование сохранённых моделей
     */
    public DeliveryRepository(JdbcTemplate jdbc, DeliveryCodec codec) { this.jdbc = jdbc; this.codec = codec; }

    /**
     * Получает общую блокировку приёмки до завершения транзакции. Одновременные сообщения обрабатываются
     * последовательно, чтобы проверка повторов и запись новой поставки не расходились.
     */
    public void lockIngress() {
        jdbc.execute("SELECT pg_advisory_xact_lock(731004)");
    }

    /**
     * Возвращает число магазинов с указанным идентификатором. Ноль означает, что магазин не зарегистрирован.
     *
     * @param store идентификатор магазина
     * @return число найденных магазинов; 0 для неизвестного идентификатора
     */
    public Integer storeCount(String store) {
        return jdbc.queryForObject("SELECT count(*) FROM stores WHERE store_id=?", Integer.class, store);
    }

    /**
     * Читает сведения об уже обработанном событии для сравнения магазина, поставки и контрольной суммы. Пустой
     * список означает, что событие ещё не зарегистрировано.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return сохранённые сведения об обработанном событии или пустой список
     */
    public List<Map<String,Object>> receivedEvents(UUID eventId) {
        return jdbc.queryForList("SELECT * FROM received_events WHERE event_id=?", eventId);
    }

    /**
     * Возвращает контрольную сумму сохранённой поставки этого магазина. Пустой список означает отсутствие
     * поставки; найденное значение нужно для сравнения повторного содержимого.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return контрольная сумма сохранённой поставки или пустой список, если её нет
     */
    public List<String> deliveryFingerprints(String store, String delivery) {
        return jdbc.queryForList("SELECT fingerprint FROM deliveries WHERE store_id=? AND delivery_id=?", String.class, store, delivery);
    }

    /**
     * Увеличивает счётчик поставок магазина на один и возвращает новый порядковый номер. Пустой список
     * означает отсутствие магазина или достижение предельного номера; изменение входит в транзакцию приёмки.
     *
     * @param store идентификатор магазина
     * @return новый номер поставки или пустой список при отсутствии магазина или исчерпанном счётчике
     */
    public List<Long> nextSequence(String store) {
        return jdbc.queryForList("""
                    UPDATE stores SET delivery_sequence=delivery_sequence+1
                    WHERE store_id=? AND delivery_sequence < 9007199254740991 RETURNING delivery_sequence
                    """, Long.class, store);
    }

    /**
     * Сохраняет поставку, порядок первой приёмки, состояние, даты и исходное содержимое. Для отклонённой
     * поставки сохраняет также причину отказа; запись входит в транзакцию приёмки.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
     * @param state состояние поставки: {@code WAITING_PRICING}, {@code POSTED} или {@code REJECTED}
     * @param receivedAt время первой приёмки поставки в WAREHOUSE
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @param failure JSON с кодом и пояснением ошибки расчёта или {@code null}, если ошибки нет
     * @param payload JSON события для очереди отправки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertDelivery(String store, String delivery, long sequence, String fingerprint, String state, Timestamp receivedAt, Timestamp nextAttemptAt, String failure, String payload) {
        return jdbc.update("""
                    INSERT INTO deliveries(store_id,delivery_id,delivery_sequence,fingerprint,state,received_at,next_attempt_at,last_error,original_payload)
                    VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """, store, delivery, sequence, fingerprint, state, receivedAt, nextAttemptAt, failure, payload);
    }

    /**
     * Сохраняет проверенную строку поставки с количеством и закупочной ценой. Продажная цена и тариф будут
     * записаны после расчёта.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param lineId идентификатор строки внутри поставки
     * @param product идентификатор продукта
     * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
     * @param shortName краткое название товара
     * @param description описание товара
     * @param quantity количество единиц товара
     * @param price закупочная цена как точное десятичное число
     * @param currency код валюты; в текущем контракте разрешён {@code RUB}
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertInputLine(String store, String delivery, String lineId, String product, String productType, String shortName, String description, int quantity, BigDecimal price, String currency) {
        return jdbc.update("""
                    INSERT INTO delivery_items(store_id,delivery_id,line_id,product_id,product_type,short_name,description,quantity,purchase_price,currency)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, store, delivery, lineId, product, productType, shortName, description, quantity, price, currency);
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
        return jdbc.update("INSERT INTO received_events(event_id,store_id,delivery_id,fingerprint) VALUES(?,?,?,?)", eventId, store, delivery, fingerprint);
    }

    /**
     * Сохраняет исходное сообщение и причину отказа по его координатам в Kafka. Повтор этих координат не
     * создаёт новую запись; ошибка БД передаётся потребителю.
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
                INSERT INTO delivery_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """, topic, partition, offset, recordedAt, code, message, raw);
    }

    /**
     * Возвращает состояние поставки и её строки. Для состояния {@code REJECTED} добавляет исходное содержимое,
     * чтобы показать причину отказа без вымышленных правильных строк.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return состояние найденной поставки или пустой список, если записи нет
     */
    public List<View> views(String store, String delivery) {
        return jdbc.query("SELECT * FROM deliveries WHERE store_id=? AND delivery_id=?", (row, number) ->
                new View(store, delivery, row.getLong("delivery_sequence"), row.getString("state"), instant(row, "received_at"),
                        instant(row, "posted_at"), row.getLong("attempt_count"), instant(row, "next_attempt_at"),
                        row.getString("last_error") == null ? null : codec.restore(row.getString("last_error"), Failure.class),
                        lines(store, delivery), "REJECTED".equals(row.getString("state")) ? codec.read(row.getString("original_payload")) : null), store, delivery);
    }

    /**
     * Читает строки поставки в порядке {@code lineId}. Цены возвращает точными строками; пока расчёт не
     * выполнен, его поля содержат {@code null}.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return строки поставки в порядке их идентификаторов; при отсутствии строк список пуст
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
     * Читает состояние и блокирует поставку до конца транзакции. Пустой список означает отсутствие поставки;
     * решение об ошибке принимает сервис.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return состояние заблокированной поставки или пустой список, если записи нет
     */
    public List<Map<String,Object>> lockDelivery(String store, String delivery) {
        return jdbc.queryForList("SELECT state FROM deliveries WHERE store_id=? AND delivery_id=? FOR UPDATE", store, delivery);
    }

    /**
     * Меняет время следующего расчёта, сохраняя владельца и срок уже выполняемой попытки. Сервис должен
     * заранее проверить состояние поставки.
     *
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int schedulePricing(Timestamp nextAttemptAt, String store, String delivery) {
        return jdbc.update("UPDATE deliveries SET next_attempt_at=? WHERE store_id=? AND delivery_id=?", nextAttemptAt, store, delivery);
    }

    /**
     * Выбирает одну поставку, ожидающую расчёта, с наступившим временем попытки и без действующего владельца.
     * Блокирует её до конца транзакции, пропуская занятые строки без ожидания.
     *
     * @param dueAt момент, до которого включительно время попытки считается наступившим
     * @param expiredAt момент, до которого включительно срок владения считается истёкшим
     * @return одна заблокированная поставка с городом и номером попытки или пустой список
     */
    public List<Map<String,Object>> lockDuePricing(Timestamp dueAt, Timestamp expiredAt) {
        return jdbc.queryForList("""
                SELECT d.store_id,d.delivery_id,s.city,d.attempt_count FROM deliveries d JOIN stores s USING(store_id)
                WHERE d.state='WAITING_PRICING' AND d.next_attempt_at<=? AND (d.lease_until IS NULL OR d.lease_until<=?)
                ORDER BY d.next_attempt_at,d.store_id,d.delivery_sequence LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """, dueAt, expiredAt);
    }

    /**
     * Назначает владельца и срок расчёта и увеличивает номер попытки. Сервис сохраняет эту транзакцию до
     * HTTP-запроса тарифов.
     *
     * @param token UUID текущего владельца фоновой попытки
     * @param leaseUntil время окончания права текущего владельца обрабатывать запись
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int claimPricing(UUID token, Timestamp leaseUntil, String store, String delivery) {
        return jdbc.update("UPDATE deliveries SET lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE store_id=? AND delivery_id=?", token, leaseUntil, store, delivery);
    }

    /**
     * Продлевает срок расчёта, только если поставка всё ещё в {@code WAITING_PRICING} и принадлежит указанному
     * владельцу. Ноль означает, что продление не выполнено.
     *
     * @param leaseUntil время окончания права текущего владельца обрабатывать запись
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param token UUID текущего владельца фоновой попытки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int renewPricing(Timestamp leaseUntil, String store, String delivery, UUID token) {
        return jdbc.update("UPDATE deliveries SET lease_until=? WHERE store_id=? AND delivery_id=? AND lease_token=? AND state='WAITING_PRICING'", leaseUntil, store, delivery, token);
    }

    /**
     * Блокирует поставку и читает идентификатор текущего владельца. Сервис сравнивает его перед сохранением
     * рассчитанных цен.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return идентификатор владельца поставки или пустой список, если записи нет
     */
    public List<String> lockPricingToken(String store, String delivery) {
        return jdbc.queryForList("SELECT lease_token::text FROM deliveries WHERE store_id=? AND delivery_id=? FOR UPDATE", String.class, store, delivery);
    }

    /**
     * Сохраняет наценку, UUID и версию правила и продажную цену одной строки. Запись входит в общую транзакцию
     * оприходования всей поставки.
     *
     * @param rate наценка как точное десятичное число
     * @param rule UUID применённого тарифного правила
     * @param version версия применённого тарифного правила
     * @param price рассчитанная продажная цена
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param lineId идентификатор строки внутри поставки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int updatePricedLine(BigDecimal rate, UUID rule, long version, BigDecimal price, String store, String delivery, String lineId) {
        return jdbc.update("""
                UPDATE delivery_items SET markup_rate=?,tariff_rule_id=?,tariff_version=?,sale_price=?
                WHERE store_id=? AND delivery_id=? AND line_id=?
                """, rate, rule, version, price, store, delivery, lineId);
    }

    /**
     * Сохраняет событие {@code GoodsPosted} в очереди отправки {@code outbox}. Событие и переход поставки в
     * {@code POSTED} должны быть сохранены в одной транзакции.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param payload JSON события для очереди отправки
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertOutbox(UUID eventId, String store, String delivery, String payload, Timestamp nextAttemptAt) {
        return jdbc.update("INSERT INTO warehouse_outbox(event_id,store_id,delivery_id,payload,next_attempt_at) VALUES(?,?,?,?,?)", eventId, store, delivery, payload, nextAttemptAt);
    }

    /**
     * Отмечает поставку как оприходованную, сохраняет время и очищает ожидание, ошибку и владельца. Сервис
     * выполняет это вместе с записью события в очередь отправки.
     *
     * @param postedAt время завершения расчёта и оприходования поставки в WAREHOUSE
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int markPosted(Timestamp postedAt, String store, String delivery) {
        return jdbc.update("""
                UPDATE deliveries SET state='POSTED',posted_at=?,next_attempt_at=NULL,last_error=NULL,lease_token=NULL,lease_until=NULL
                WHERE store_id=? AND delivery_id=?
                """, postedAt, store, delivery);
    }

    /**
     * Сохраняет ошибку расчёта, назначает повтор и освобождает поставку, только если она ещё ожидает расчёта и
     * принадлежит указанному владельцу.
     *
     * @param failure JSON с кодом и пояснением ошибки расчёта или {@code null}, если ошибки нет
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param token UUID текущего владельца фоновой попытки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int schedulePricingRetry(String failure, Timestamp nextAttemptAt, String store, String delivery, UUID token) {
        return jdbc.update("""
                UPDATE deliveries SET last_error=?::jsonb,next_attempt_at=?,lease_token=NULL,lease_until=NULL
                WHERE store_id=? AND delivery_id=? AND lease_token=? AND state='WAITING_PRICING'
                """, failure, nextAttemptAt, store, delivery, token);
    }

    /**
     * Выбирает одно ожидающее событие, для которого наступило время отправки и нет действующего владельца.
     * Блокирует его до конца транзакции, пропуская занятые строки без ожидания.
     *
     * @param dueAt момент, до которого включительно время попытки считается наступившим
     * @param expiredAt момент, до которого включительно срок владения считается истёкшим
     * @return одно заблокированное событие с данными отправки или пустой список, если подходящего события нет
     */
    public List<Map<String,Object>> lockDueOutbox(Timestamp dueAt, Timestamp expiredAt) {
        return jdbc.queryForList("""
                SELECT event_id,store_id,payload,attempt_count FROM warehouse_outbox
                WHERE publication_status='PENDING' AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, dueAt, expiredAt);
    }

    /**
     * Назначает владельца и срок отправки и увеличивает номер попытки. Сервис сохраняет эту транзакцию до
     * обращения к Kafka.
     *
     * @param token UUID текущего владельца фоновой попытки
     * @param leaseUntil время окончания права текущего владельца обрабатывать запись
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int claimOutbox(UUID token, Timestamp leaseUntil, UUID eventId) {
        return jdbc.update("UPDATE warehouse_outbox SET lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE event_id=?", token, leaseUntil, eventId);
    }

    /**
     * Отмечает подтверждённую Kafka публикацию, только если событие ещё ожидает отправки и принадлежит
     * указанному владельцу. Возвращает 0, если условие уже не выполнено.
     *
     * @param publishedAt время подтверждения отправки события в Kafka; до отправки может отсутствовать
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param token UUID текущего владельца фоновой попытки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int markPublished(Timestamp publishedAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE warehouse_outbox SET publication_status='PUBLISHED',published_at=?,last_error=NULL,lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND lease_token=? AND publication_status='PENDING'
                """, publishedAt, eventId, token);
    }

    /**
     * Сохраняет причину сбоя отправки, назначает повтор и освобождает событие, только если оно принадлежит
     * указанному владельцу и ещё ожидает публикации.
     *
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param token UUID текущего владельца фоновой попытки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int scheduleSendRetry(Timestamp nextAttemptAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE warehouse_outbox SET last_error='Kafka publication unavailable',next_attempt_at=?,lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND lease_token=? AND publication_status='PENDING'
                """, nextAttemptAt, eventId, token);
    }
    /**
     * Читает дату из колонки SQL-результата и возвращает тот же момент как {@code Instant}. Отсутствующую дату
     * сохраняет как {@code null}; ошибку чтения передаёт как {@code SQLException}.
     *
     * @param row текущая строка результата SQL-запроса
     * @param field имя читаемого поля
     * @return момент времени; для отсутствующей даты в БД {@code null}
     */
    private Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
