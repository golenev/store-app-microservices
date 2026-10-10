package com.shop.store.repository;

import com.shop.store.dto.Submission;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Выполняет SQL для SubmissionRepository; блокировки и записи входят в транзакцию вызывающего сервиса. */
@Repository
public class SubmissionRepository {
    private final JdbcTemplate jdbc;

    /**
     * Получает JDBC для участия в транзакциях сервиса; конструктор не обращается к БД.
     *
     * @param jdbc JDBC-адаптер с участием в текущей транзакции Spring
     */
    public SubmissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Блокирует корзину магазина до конца оформления; пустой список означает отсутствие корзины. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> lockCart(String store, UUID cart) {
        return jdbc.queryForList("SELECT state,version FROM carts WHERE store_id=? AND cart_id=? FOR UPDATE", store, cart);
    }

    /**
     * Блокирует остатки корзины в порядке UUID; блокировки сохраняются до завершения оформления. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> lockStockLines(String store, UUID cart) {
        return jdbc.queryForList("""
                SELECT i.stock_item_id,i.product_id,i.short_name,i.unit_price,i.available_quantity,c.quantity
                FROM cart_items c JOIN inventory i ON i.store_id=c.store_id AND i.stock_item_id=c.stock_item_id
                WHERE c.store_id=? AND c.cart_id=? ORDER BY i.stock_item_id FOR UPDATE OF i
                """, store, cart);
    }

    /**
     * Сохраняет заявку с ключом и неизменяемым снимком; конфликт уникальности откатывает оформление целиком.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param submission UUID принятой заявки
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param key регистрозависимый ключ операции внутри магазина
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @param version версия правила или корзины согласно операции
     * @param eventId идентификатор события для защиты повторов
     * @param acceptedAt UTC-время принятия заявки
     * @param snapshot неизменяемый снимок принятой корзины в JSON
     * @return число изменённых строк
     */
    public int insertSubmission(UUID submission, String store, UUID cart, String key, String fingerprint, long version, UUID eventId, Timestamp acceptedAt, String snapshot) {
        return jdbc.update("""
                INSERT INTO submissions(submission_id,store_id,cart_id,idempotency_key,request_fingerprint,expected_cart_version,event_id,accepted_at,cart_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, submission, store, cart, key, fingerprint, version, eventId, acceptedAt, snapshot);
    }

    /**
     * Списывает проверенное сервисом количество с предварительно заблокированной позиции. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param store идентификатор магазина
     * @param stock UUID позиции остатка
     * @return число изменённых строк
     */
    public int deductStock(int quantity, String store, UUID stock) {
        return jdbc.update("UPDATE inventory SET available_quantity=available_quantity-? WHERE store_id=? AND stock_item_id=?", quantity, store, stock);
    }

    /**
     * Записывает расход по заявке в общей транзакции со списанием остатка. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param store идентификатор магазина
     * @param submission UUID принятой заявки
     * @param stock UUID позиции остатка
     * @param quantity итоговое количество позиции или величина движения согласно операции
     * @param price точная денежная цена без floating point
     * @param acceptedAt UTC-время принятия заявки
     * @return число изменённых строк
     */
    public int insertExpense(String store, UUID submission, UUID stock, int quantity, BigDecimal price, Timestamp acceptedAt) {
        return jdbc.update("INSERT INTO stock_expenses(store_id,submission_id,stock_item_id,quantity,unit_price,accepted_at) VALUES(?,?,?,?,?,?)", store, submission, stock, quantity, price, acceptedAt);
    }

    /**
     * Сохраняет неизменяемое событие заявки для последующей публикации после фиксации оформления. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param eventId идентификатор события для защиты повторов
     * @param submission UUID принятой заявки
     * @param store идентификатор магазина
     * @param payload сериализованное содержимое события или проверенный объект согласно типу
     * @param nextAttemptAt UTC-время следующей попытки
     * @return число изменённых строк
     */
    public int insertOutbox(UUID eventId, UUID submission, String store, String payload, Timestamp nextAttemptAt) {
        return jdbc.update("INSERT INTO store_outbox(event_id,submission_id,store_id,payload,next_attempt_at) VALUES(?,?,?,?,?)", eventId, submission, store, payload, nextAttemptAt);
    }

    /**
     * Закрывает корзину и увеличивает версию в общей транзакции оформления. Транзакцией управляет вызывающий
     * сервис.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return число изменённых строк
     */
    public int closeCart(String store, UUID cart) {
        return jdbc.update("UPDATE carts SET state='SUBMITTED',version=version+1 WHERE store_id=? AND cart_id=?", store, cart);
    }

    /**
     * Читает заявку и отпечаток по ключу внутри магазина для идемпотентного повтора. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param key регистрозависимый ключ операции внутри магазина
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> findByKey(String store, String key) {
        return jdbc.queryForList("SELECT submission_id,request_fingerprint FROM submissions WHERE store_id=? AND idempotency_key=?", store, key);
    }

    /**
     * Читает заявку и статус публикации одним SQL-запросом; пустой список означает отсутствие операции.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param store идентификатор магазина
     * @param id UUID запрашиваемого объекта
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Submission> views(String store, UUID id) {
        return jdbc.query("""
                SELECT s.store_id,s.submission_id,s.cart_id,s.event_id,s.accepted_at,o.publication_status,o.published_at
                FROM submissions s JOIN store_outbox o ON o.submission_id=s.submission_id
                WHERE s.store_id=? AND s.submission_id=?
                """, (row,number) -> new Submission(row.getString("store_id"),row.getObject("submission_id",UUID.class),
                row.getObject("cart_id",UUID.class),row.getObject("event_id",UUID.class),row.getString("publication_status"),
                row.getTimestamp("accepted_at").toInstant(),row.getTimestamp("published_at")==null?null:row.getTimestamp("published_at").toInstant()), store, id);
    }

    /**
     * Выбирает одно наступившее событие с SKIP LOCKED; просроченный захват допускает восстановление.
     * Транзакцией управляет вызывающий сервис.
     *
     * @param dueAt UTC-граница наступившей попытки
     * @param expiredAt UTC-граница просроченного захвата
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Map<String,Object>> lockDueOutbox(Timestamp dueAt, Timestamp expiredAt) {
        return jdbc.queryForList("""
                SELECT event_id,store_id,payload,attempt_count FROM store_outbox WHERE publication_status='PENDING'
                AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, dueAt, expiredAt);
    }

    /**
     * Сохраняет владельца и число попыток под блокировкой события перед обращением к Kafka. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param token токен текущего владельца захвата
     * @param leaseUntil UTC-время окончания захвата
     * @param attempts сохраняемое число попыток отправки
     * @param eventId идентификатор события для защиты повторов
     * @return число изменённых строк
     */
    public int claimOutbox(UUID token, Timestamp leaseUntil, int attempts, UUID eventId) {
        return jdbc.update("UPDATE store_outbox SET lease_token=?,lease_until=?,attempt_count=? WHERE event_id=?", token, leaseUntil, attempts, eventId);
    }

    /**
     * Фиксирует подтверждение брокера только для текущего владельца PENDING-события. Транзакцией управляет
     * вызывающий сервис.
     *
     * @param publishedAt UTC-время подтверждения публикации
     * @param eventId идентификатор события для защиты повторов
     * @param token токен текущего владельца захвата
     * @return число изменённых строк
     */
    public int markPublished(Timestamp publishedAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE store_outbox SET publication_status='PUBLISHED',published_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND publication_status='PENDING' AND lease_token=?
                """, publishedAt, eventId, token);
    }

    /**
     * Сохраняет повторную попытку для текущего владельца, не изменяя списание и снимок заявки. Транзакцией
     * управляет вызывающий сервис.
     *
     * @param nextAttemptAt UTC-время следующей попытки
     * @param eventId идентификатор события для защиты повторов
     * @param token токен текущего владельца захвата
     * @return число изменённых строк
     */
    public int scheduleSendRetry(Timestamp nextAttemptAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE store_outbox SET next_attempt_at=?,last_error='Kafka acknowledgement unavailable',lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND publication_status='PENDING' AND lease_token=?
                """, nextAttemptAt, eventId, token);
    }
}
