package com.shop.store.repository;

import com.shop.store.dto.Submission;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Сохраняет заявки, списания и очередь исходящих событий в PostgreSQL. Выполняет SQL внутри транзакции
 * вызывающего сервиса.
 */
@Repository
public class SubmissionRepository {
    private final JdbcTemplate jdbc;

    /**
     * Подключает выполнение SQL к текущей транзакции Spring.
     *
     * @param jdbc выполнение SQL с участием в текущей транзакции Spring
     */
    public SubmissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Блокирует корзину указанного магазина до завершения оформления. Пустой список означает, что корзина не
     * найдена.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return состояние и версия заблокированной корзины; пустой список, если корзина отсутствует
     */
    public List<Map<String,Object>> lockCart(String store, UUID cart) {
        return jdbc.queryForList("SELECT state,version FROM carts WHERE store_id=? AND cart_id=? FOR UPDATE", store, cart);
    }

    /**
     * Читает позиции корзины и блокирует их остатки в порядке UUID. Блокировки действуют до завершения
     * оформления и не позволяют другому запросу одновременно списать тот же товар.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return позиции корзины с текущими ценами и заблокированными остатками; пустой список, если позиций нет
     */
    public List<Map<String,Object>> lockStockLines(String store, UUID cart) {
        return jdbc.queryForList("""
                SELECT i.stock_item_id,i.product_id,i.short_name,i.unit_price,i.available_quantity,c.quantity
                FROM cart_items c JOIN inventory i ON i.store_id=c.store_id AND i.stock_item_id=c.stock_item_id
                WHERE c.store_id=? AND c.cart_id=? ORDER BY i.stock_item_id FOR UPDATE OF i
                """, store, cart);
    }

    /**
     * Сохраняет заявку, ключ повтора и снимок оформленной корзины. Конфликт уникальности прерывает транзакцию
     * оформления, чтобы все её записи были отменены вместе.
     *
     * @param submission UUID принятой заявки
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param key ключ распознавания повторного оформления внутри магазина
     * @param fingerprint контрольная сумма магазина, корзины и ожидаемой версии запроса оформления
     * @param version ожидаемая версия корзины до оформления
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param acceptedAt время принятия заявки на оформление
     * @param snapshot JSON-снимок корзины на момент принятия заявки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertSubmission(UUID submission, String store, UUID cart, String key, String fingerprint, long version, UUID eventId, Timestamp acceptedAt, String snapshot) {
        return jdbc.update("""
                INSERT INTO submissions(submission_id,store_id,cart_id,idempotency_key,request_fingerprint,expected_cart_version,event_id,accepted_at,cart_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, submission, store, cart, key, fingerprint, version, eventId, acceptedAt, snapshot);
    }

    /**
     * Уменьшает остаток на указанное количество. Сервис должен заранее заблокировать позицию и проверить, что
     * товара достаточно.
     *
     * @param quantity количество единиц товара для списания
     * @param store идентификатор магазина
     * @param stock UUID позиции остатка магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int deductStock(int quantity, String store, UUID stock) {
        return jdbc.update("UPDATE inventory SET available_quantity=available_quantity-? WHERE store_id=? AND stock_item_id=?", quantity, store, stock);
    }

    /**
     * Сохраняет движение расхода по заявке. Запись и уменьшение остатка входят в одну транзакцию оформления.
     *
     * @param store идентификатор магазина
     * @param submission UUID принятой заявки
     * @param stock UUID позиции остатка магазина
     * @param quantity количество единиц товара в движении расхода
     * @param price продажная цена как точное десятичное число
     * @param acceptedAt время принятия заявки на оформление
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertExpense(String store, UUID submission, UUID stock, int quantity, BigDecimal price, Timestamp acceptedAt) {
        return jdbc.update("INSERT INTO stock_expenses(store_id,submission_id,stock_item_id,quantity,unit_price,accepted_at) VALUES(?,?,?,?,?,?)", store, submission, stock, quantity, price, acceptedAt);
    }

    /**
     * Сохраняет JSON события в очереди отправки {@code outbox}. Событие становится доступно отправителю после
     * сохранения всей транзакции оформления.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param submission UUID принятой заявки
     * @param store идентификатор магазина
     * @param payload JSON события для очереди отправки
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertOutbox(UUID eventId, UUID submission, String store, String payload, Timestamp nextAttemptAt) {
        return jdbc.update("INSERT INTO store_outbox(event_id,submission_id,store_id,payload,next_attempt_at) VALUES(?,?,?,?,?)", eventId, submission, store, payload, nextAttemptAt);
    }

    /**
     * Переводит корзину в оформленное состояние и увеличивает версию на один в транзакции оформления.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int closeCart(String store, UUID cart) {
        return jdbc.update("UPDATE carts SET state='SUBMITTED',version=version+1 WHERE store_id=? AND cart_id=?", store, cart);
    }

    /**
     * Возвращает идентификатор заявки и контрольную сумму запроса по ключу внутри магазина. Пустой список
     * означает, что ключ ещё не использован.
     *
     * @param store идентификатор магазина
     * @param key ключ распознавания повторного оформления внутри магазина
     * @return идентификатор заявки и контрольная сумма запроса или пустой список для свободного ключа
     */
    public List<Map<String,Object>> findByKey(String store, String key) {
        return jdbc.queryForList("SELECT submission_id,request_fingerprint FROM submissions WHERE store_id=? AND idempotency_key=?", store, key);
    }

    /**
     * Одним SQL-запросом читает заявку и состояние отправки события. Пустой список означает отсутствие
     * операции в указанном магазине.
     *
     * @param store идентификатор магазина
     * @param id UUID принятой заявки
     * @return состояние найденной заявки или пустой список, если записи нет
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
     * Выбирает одно ожидающее событие, для которого наступило время отправки и нет действующего владельца.
     * Блокирует его до конца транзакции, пропуская занятые строки без ожидания.
     *
     * @param dueAt момент, до которого включительно время попытки считается наступившим
     * @param expiredAt момент, до которого включительно срок владения считается истёкшим
     * @return одно заблокированное событие с данными отправки или пустой список, если подходящего события нет
     */
    public List<Map<String,Object>> lockDueOutbox(Timestamp dueAt, Timestamp expiredAt) {
        return jdbc.queryForList("""
                SELECT event_id,store_id,payload,attempt_count FROM store_outbox WHERE publication_status='PENDING'
                AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, dueAt, expiredAt);
    }

    /**
     * Назначает владельца заблокированного события, срок его работы и номер попытки. Запись сохраняется до
     * обращения отправителя к Kafka.
     *
     * @param token UUID текущего владельца фоновой попытки
     * @param leaseUntil время окончания права текущего владельца обрабатывать запись
     * @param attempts номер текущей попытки отправки
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int claimOutbox(UUID token, Timestamp leaseUntil, int attempts, UUID eventId) {
        return jdbc.update("UPDATE store_outbox SET lease_token=?,lease_until=?,attempt_count=? WHERE event_id=?", token, leaseUntil, attempts, eventId);
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
                UPDATE store_outbox SET publication_status='PUBLISHED',published_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND publication_status='PENDING' AND lease_token=?
                """, publishedAt, eventId, token);
    }

    /**
     * Назначает время повторной отправки и освобождает событие, только если оно принадлежит указанному
     * владельцу. Заявка и списанные остатки не меняются.
     *
     * @param nextAttemptAt время, начиная с которого разрешена следующая попытка
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @param token UUID текущего владельца фоновой попытки
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int scheduleSendRetry(Timestamp nextAttemptAt, UUID eventId, UUID token) {
        return jdbc.update("""
                UPDATE store_outbox SET next_attempt_at=?,last_error='Kafka acknowledgement unavailable',lease_token=NULL,lease_until=NULL
                WHERE event_id=? AND publication_status='PENDING' AND lease_token=?
                """, nextAttemptAt, eventId, token);
    }
}
