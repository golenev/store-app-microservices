package com.shop.store.repository;

import com.shop.store.dto.Submission;

import jakarta.persistence.EntityManager;
import com.shop.store.entity.*;
import com.shop.store.repository.jpa.*;
import com.shop.store.model.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Сохраняет заявки, списания и очередь исходящих событий в PostgreSQL. Выполняет операции JPA внутри транзакции
 * вызывающего сервиса.
 */
@Repository
public class SubmissionRepository {
    private final EntityManager entities;
    private final CartJpaRepository carts;
    private final CartItemJpaRepository items;
    private final InventoryJpaRepository stocks;
    private final SubmissionJpaRepository submissions;
    private final OutboxJpaRepository outbox;

    /**
     * Подключает контекст и Spring Data JPA; persist сохраняет вставку неизменяемой истории,
     * flush выявляет нарушения UNIQUE внутри транзакции до возврата принятой операции.
     * @param entities контекст текущей транзакции
     * @param carts корзины
     * @param items позиции
     * @param stocks остатки и их блокировки
     * @param submissions принятые заявки
     * @param outbox исходящие события
     */
    public SubmissionRepository(EntityManager entities, CartJpaRepository carts, CartItemJpaRepository items, InventoryJpaRepository stocks, SubmissionJpaRepository submissions, OutboxJpaRepository outbox) {
        this.entities = entities;
        this.carts = carts;
        this.items = items;
        this.stocks = stocks;
        this.submissions = submissions;
        this.outbox = outbox;
    }

    /**
     * Блокирует корзину указанного магазина до завершения оформления. Пустой список означает, что корзина не
     * найдена.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return состояние и версия заблокированной корзины; пустой список, если корзина отсутствует
     */
    public List<CartHeader> lockCart(String store, UUID cart) {
        return carts.lockCart(store, cart).map(entity -> List.of(new CartHeader(entity.getState(), entity.getVersion()))).orElseGet(List::of);
    }

    /**
     * Читает позиции корзины и блокирует их остатки в порядке UUID. Блокировки действуют до завершения
     * оформления и не позволяют другому запросу одновременно списать тот же товар.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return позиции корзины с текущими ценами и заблокированными остатками; пустой список, если позиций нет
     */
    public List<CheckoutLine> lockStockLines(String store, UUID cart) {
        stocks.lockCheckout(store, cart);
        return items.lines(store, cart, PageRequest.of(0, 1001));
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
        entities.persist(SubmissionEntity.builder().submissionId(submission).storeId(store).cartId(cart)
                .idempotencyKey(key).requestFingerprint(fingerprint).expectedCartVersion(version)
                .eventId(eventId).acceptedAt(acceptedAt.toInstant()).cartSnapshot(snapshot).build());
        entities.flush();
        return 1;
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
        InventoryEntity entity = entities.find(InventoryEntity.class, stock);
        if (entity == null || !entity.getStoreId().equals(store)) return 0;
        entity.setAvailableQuantity(entity.getAvailableQuantity() - quantity);
        return 1;
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
        entities.persist(StockExpenseEntity.builder().storeId(store).submissionId(submission).stockItemId(stock)
                .quantity(quantity).unitPrice(price).acceptedAt(acceptedAt.toInstant()).build());
        entities.flush();
        return 1;
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
        entities.persist(OutboxEntity.builder().eventId(eventId).submissionId(submission).storeId(store)
                .payload(payload).nextAttemptAt(nextAttemptAt.toInstant()).publicationStatus("PENDING").attemptCount(0).build());
        entities.flush();
        return 1;
    }

    /**
     * Переводит корзину в оформленное состояние и увеличивает версию на один в транзакции оформления.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int closeCart(String store, UUID cart) {
        var found = carts.findByStoreIdAndCartId(store, cart);
        if (found.isEmpty()) return 0;
        CartEntity entity = found.get();
        entity.setState("SUBMITTED");
        entity.setVersion(entity.getVersion() + 1);
        return 1;
    }

    /**
     * Возвращает идентификатор заявки и контрольную сумму запроса по ключу внутри магазина. Пустой список
     * означает, что ключ ещё не использован.
     *
     * @param store идентификатор магазина
     * @param key ключ распознавания повторного оформления внутри магазина
     * @return идентификатор заявки и контрольная сумма запроса или пустой список для свободного ключа
     */
    public List<SubmissionMatch> findByKey(String store, String key) {
        return submissions.findMatch(store, key);
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
        return submissions.views(store, id);
    }

    /**
     * Выбирает одно ожидающее событие, для которого наступило время отправки и нет действующего владельца.
     * Блокирует его до конца транзакции, пропуская занятые строки без ожидания.
     *
     * @param dueAt момент, до которого включительно время попытки считается наступившим
     * @param expiredAt момент, до которого включительно срок владения считается истёкшим
     * @return одно заблокированное событие с данными отправки или пустой список, если подходящего события нет
     */
    public List<OutboxCandidate> lockDueOutbox(Timestamp dueAt, Timestamp expiredAt) {
        return outbox.lockDue(dueAt.toInstant(), expiredAt.toInstant()).stream()
                .map(entity -> new OutboxCandidate(entity.getEventId(), entity.getStoreId(), entity.getPayload(), entity.getAttemptCount()))
                .toList();
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
        OutboxEntity entity = entities.find(OutboxEntity.class, eventId);
        if (entity == null) return 0;
        entity.setLeaseToken(token);
        entity.setLeaseUntil(leaseUntil.toInstant());
        entity.setAttemptCount(attempts);
        return 1;
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
        return outbox.published(publishedAt.toInstant(), eventId, token);
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
        return outbox.retry(nextAttemptAt.toInstant(), eventId, token);
    }
}
