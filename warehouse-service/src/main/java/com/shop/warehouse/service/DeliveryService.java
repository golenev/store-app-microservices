package com.shop.warehouse.service;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Failure;
import com.shop.warehouse.dto.Line;
import com.shop.warehouse.dto.View;
import com.shop.warehouse.exception.DeliveryException;
import com.shop.warehouse.messaging.dto.GoodsEvent;
import com.shop.warehouse.messaging.dto.GoodsPayload;
import com.shop.warehouse.messaging.dto.InputLine;
import com.shop.warehouse.model.Accepted;
import com.shop.warehouse.model.OutboxWork;
import com.shop.warehouse.model.PricingWork;
import com.shop.warehouse.repository.DeliveryRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Сохраняет приёмку поставок, результаты расчёта цен и очередь исходящих событий. HTTP-запросы тарифов и
 * отправка в Kafka выполняются за пределами этих транзакций.
 */
@Service
public class DeliveryService {
    private final DeliveryRepository repository;
    private final DeliveryCodec codec;
    private final Clock clock;
    private final long leaseMillis;

    /**
     * Подключает хранение поставок, обработку JSON и часы. Задаёт срок, в течение которого поставка или
     * событие принадлежит выбранному фоновому обработчику.
     *
     * @param repository чтение и запись поставок и очереди событий
     * @param codec проверка событий поставок и преобразование сохранённых моделей
     * @param clock часы для дат операций и сроков фоновых попыток
     * @param leaseMillis срок владения поставкой или событием в миллисекундах
     */
    public DeliveryService(DeliveryRepository repository, DeliveryCodec codec, Clock clock,
                         @Value("${warehouse.lease-ms:30000}") long leaseMillis) {
        this.repository = repository;
        this.codec = codec;
        this.clock = clock;
        this.leaseMillis = leaseMillis;
    }

    /**
     * Сохраняет новую поставку или причину отказа в одной транзакции до подтверждения обработки сообщения
     * Kafka. Общая блокировка приёмки не позволяет одновременно проверить и записать один и тот же повтор.
     * Совпадающие идентификаторы и содержимое не создают вторую поставку; конфликт сохраняется для
     * диагностики. Ошибку БД передаёт потребителю для повторной обработки.
     *
     * @param topic имя канала Kafka, из которого получено сообщение
     * @param partition номер раздела канала Kafka
     * @param offset позиция сообщения внутри раздела Kafka
     * @param key ключ сообщения Kafka, который должен совпадать с идентификатором магазина
     * @param raw исходный текст JSON-сообщения
     */
    @Transactional
    public void receive(String topic, int partition, long offset, String key, String raw) {
        repository.lockIngress();
        Accepted input;
        try { input = codec.decode(raw); }
        catch (DeliveryException failure) { diagnostic(topic, partition, offset, raw, failure.code(), failure.getMessage()); return; }
        if (!input.storeId().equals(key)) {
            diagnostic(topic, partition, offset, raw, "VALIDATION_ERROR", "Kafka key must equal storeId"); return;
        }
        if (repository.storeCount(input.storeId()) == 0) {
            diagnostic(topic, partition, offset, raw, "NOT_FOUND", "Unknown store"); return;
        }
        List<Map<String, Object>> events = repository.receivedEvents(input.eventId());
        if (!events.isEmpty()) {
            Map<String, Object> previous = events.getFirst();
            if (!input.storeId().equals(previous.get("store_id")) || !input.deliveryId().equals(previous.get("delivery_id"))
                    || !input.fingerprint().equals(previous.get("fingerprint")))
                diagnostic(topic, partition, offset, raw, "DELIVERY_CONTENT_CONFLICT", "eventId has different business content");
            return;
        }
        List<String> fingerprints = repository.deliveryFingerprints(input.storeId(), input.deliveryId());
        if (!fingerprints.isEmpty() && !fingerprints.getFirst().equals(input.fingerprint())) {
            diagnostic(topic, partition, offset, raw, "DELIVERY_CONTENT_CONFLICT", "deliveryId has different business content"); return;
        }
        if (fingerprints.isEmpty()) {
            List<Long> sequences = repository.nextSequence(input.storeId());
            if (sequences.isEmpty()) {
                diagnostic(topic, partition, offset, raw, "DEPENDENCY_UNAVAILABLE", "Store delivery sequence is exhausted"); return;
            }
            long sequence = sequences.getFirst();
            Instant now = clock.instant();
            repository.insertDelivery(input.storeId(), input.deliveryId(), sequence, input.fingerprint(), input.rejection() == null ? "WAITING_PRICING" : "REJECTED", timestamp(now), input.rejection() == null ? timestamp(now) : null, input.rejection() == null ? null : codec.json(input.rejection()), codec.json(input.payload()));
            for (InputLine line : input.items()) repository.insertInputLine(input.storeId(), input.deliveryId(), line.lineId(), line.productId(), line.productType(), line.shortName(), line.description(), line.quantity(), new BigDecimal(line.purchasePrice()), line.currency());
        }
        repository.rememberEvent(input.eventId(), input.storeId(), input.deliveryId(), input.fingerprint());
    }

    /**
     * Сохраняет исходное сообщение и причину отказа в текущей транзакции. Повтор тех же координат Kafka не
     * создаёт вторую запись; ошибка БД передаётся потребителю.
     *
     * @param topic имя канала Kafka, из которого получено сообщение
     * @param partition номер раздела канала Kafka
     * @param offset позиция сообщения внутри раздела Kafka
     * @param raw исходный текст JSON-сообщения
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    private void diagnostic(String topic, int partition, long offset, String raw, String code, String message) {
        repository.insertDiagnostic(topic, partition, offset, timestamp(clock.instant()), code, message, raw);
    }

    /**
     * Возвращает состояние поставки и её строки из согласованного снимка БД в транзакции повторяемого чтения.
     * Для отклонённой поставки также возвращает исходное содержимое. Отсутствие поставки в указанном магазине
     * вызывает {@code NOT_FOUND}.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return состояние поставки с датами, попытками и строками
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View view(String store, String delivery) {
        List<View> rows = repository.views(store, delivery);
        if (rows.isEmpty()) throw new DeliveryException(404, "NOT_FOUND", "Delivery not found");
        return rows.getFirst();
    }

    /**
     * Назначает ближайшую попытку расчёта на текущее время для поставки в состоянии {@code WAITING_PRICING} и
     * возвращает её состояние. Выполняет изменение под блокировкой; действующий владелец поставки сохраняется.
     * Отсутствие вызывает {@code NOT_FOUND}, другое состояние — {@code DELIVERY_NOT_WAITING_PRICING} и HTTP
     * 409.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return состояние поставки с датами, попытками и строками
     */
    @Transactional
    public View retry(String store, String delivery) {
        repository.lockDelivery(store, delivery);
        View previous = view(store, delivery);
        if (!previous.state().equals("WAITING_PRICING"))
            throw new DeliveryException(409, "DELIVERY_NOT_WAITING_PRICING", "Delivery is not waiting for pricing");
        repository.schedulePricing(timestamp(clock.instant()), store, delivery);
        return view(store, delivery);
    }

    /**
     * Выбирает одну поставку, для которой наступило время расчёта и нет действующего владельца. Занятые строки
     * пропускает без ожидания. В короткой транзакции сохраняет нового владельца, срок и увеличенный номер
     * попытки; возвращает работу для последующих HTTP-запросов тарифов.
     *
     * @return поставка для расчёта или пустой результат, если подходящей поставки нет
     */
    @Transactional
    public Optional<PricingWork> claimPricing() {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = repository.lockDuePricing(timestamp(now), timestamp(now));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        String store = (String) row.get("store_id"), delivery = (String) row.get("delivery_id");
        UUID token = UUID.randomUUID();
        repository.claimPricing(token, timestamp(now.plusMillis(leaseMillis)), store, delivery);
        return Optional.of(new PricingWork(store, delivery, (String) row.get("city"), token,
                ((Number) row.get("attempt_count")).longValue() + 1, repository.lines(store, delivery)));
    }

    /**
     * Продлевает срок работы текущего владельца перед очередным запросом тарифа. Возвращает {@code false},
     * если идентификатор владельца или состояние поставки уже изменились; обработчик должен прекратить эту
     * попытку.
     *
     * @param work выбранная поставка, строки, идентификатор владельца и номер попытки
     * @return {@code true}, если срок владения продлён; {@code false}, если право на попытку потеряно
     */
    @Transactional
    public boolean renew(PricingWork work) {
        return repository.renewPricing(timestamp(clock.instant().plusMillis(leaseMillis)), work.storeId(), work.deliveryId(), work.token()) == 1;
    }

    /**
     * Сохраняет цены всех переданных строк, переводит поставку в {@code POSTED} и добавляет одно событие
     * {@code GoodsPosted} в очередь отправки. Все записи выполняет в одной транзакции под блокировкой
     * поставки; ошибка отменяет их вместе. Если владельцем уже стал другой обработчик, возвращает {@code
     * false} без изменений.
     *
     * @param work выбранная поставка, строки, идентификатор владельца и номер попытки
     * @param priced строки поставки с количеством, ценами и данными тарифа
     * @return {@code true}, если поставка оприходована; {@code false}, если владелец сменился
     */
    @Transactional
    public boolean post(PricingWork work, List<Line> priced) {
        List<String> tokens = repository.lockPricingToken(work.storeId(), work.deliveryId());
        if (tokens.isEmpty() || !work.token().toString().equals(tokens.getFirst())) return false;
        View before = view(work.storeId(), work.deliveryId());
        Instant now = clock.instant();
        Instant posted = now.isBefore(before.receivedAt()) ? before.receivedAt() : now;
        for (Line line : priced) repository.updatePricedLine(new BigDecimal(line.markupRate()), line.tariffRuleId(), line.tariffVersion(), new BigDecimal(line.salePrice()), work.storeId(), work.deliveryId(), line.lineId());
        UUID event = UUID.randomUUID();
        GoodsEvent goods = new GoodsEvent(event, "GoodsPosted", 1, posted, work.storeId(),
                new GoodsPayload(work.deliveryId(), before.deliverySequence(), before.receivedAt(), posted, List.copyOf(priced)));
        repository.insertOutbox(event, work.storeId(), work.deliveryId(), codec.json(goods), timestamp(posted));
        repository.markPosted(timestamp(posted), work.storeId(), work.deliveryId());
        return true;
    }

    /**
     * Сохраняет причину неудачного расчёта и назначает повтор с задержкой до 60 секунд. Меняет поставку только
     * для текущего владельца и освобождает её для следующей попытки; частичные цены не сохраняет.
     *
     * @param work выбранная поставка, строки, идентификатор владельца и номер попытки
     * @param failure код и пояснение неудачного расчёта или отклонения поставки
     */
    @Transactional
    public void failedPricing(PricingWork work, Failure failure) {
        repository.schedulePricingRetry(codec.json(failure), timestamp(clock.instant().plusMillis(backoff(work.attemptCount()))), work.storeId(), work.deliveryId(), work.token());
    }

    /**
     * Выбирает одно ожидающее событие и временно назначает владельца для отправки в Kafka. Занятые строки
     * пропускает; событие с истёкшим сроком допускает повтор после сбоя. Транзакция завершается до обращения к
     * Kafka.
     *
     * @return событие для отправки или пустой результат, если подходящего события нет
     */
    @Transactional
    public Optional<OutboxWork> claimOutbox() {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = repository.lockDueOutbox(timestamp(now), timestamp(now));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        UUID event = (UUID) row.get("event_id"), token = UUID.randomUUID();
        repository.claimOutbox(token, timestamp(now.plusMillis(leaseMillis)), event);
        return Optional.of(new OutboxWork(event, (String) row.get("store_id"), (String) row.get("payload"), token,
                ((Number) row.get("attempt_count")).longValue() + 1));
    }

    /**
     * После подтверждения Kafka отмечает событие как отправленное. Запись выполняется только для текущего
     * владельца, поэтому запоздалый ответ прежнего обработчика не меняет состояние.
     *
     * @param work выбранное событие, идентификатор его владельца и номер попытки
     */
    @Transactional
    public void published(OutboxWork work) {
        repository.markPublished(timestamp(clock.instant()), work.eventId(), work.token());
    }

    /**
     * Назначает повторную отправку того же события и освобождает его для следующей попытки, если владелец ещё
     * совпадает. Если Kafka приняла событие, но подтверждение потерялось, возможна повторная отправка с тем же
     * идентификатором.
     *
     * @param work выбранное событие, идентификатор его владельца и номер попытки
     */
    @Transactional
    public void failedSend(OutboxWork work) {
        repository.scheduleSendRetry(timestamp(clock.instant().plusMillis(backoff(work.attemptCount()))), work.eventId(), work.token());
    }

    /**
     * Возвращает задержку повторной попытки в миллисекундах: 1000, 2000, 4000, 8000, 16000, 32000 и далее
     * 60000. Значения попытки меньше 1 также дают 1000 миллисекунд.
     *
     * @param attempt номер попытки; первая попытка имеет номер 1
     * @return задержка перед повтором в миллисекундах, не более 60 000
     */
    private long backoff(long attempt) { return Math.min(60000L, 1000L << Math.min(6L, Math.max(0L, attempt - 1))); }

    /**
     * Переводит момент времени в SQL-тип {@code Timestamp}, сохраняя тот же момент независимо от часового
     * пояса. Для {@code null} возвращает {@code null}.
     *
     * @param value момент времени или {@code null}, если дата отсутствует
     * @return тот же момент в SQL-представлении или {@code null}
     */
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

}
