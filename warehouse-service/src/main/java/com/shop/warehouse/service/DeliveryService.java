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

/** Управляет транзакциями приёмки, расчёта и outbox; HTTP и Kafka выполняются между короткими транзакциями. */
@Service
public class DeliveryService {
    private final DeliveryRepository repository;
    private final DeliveryCodec codec;
    private final Clock clock;
    private final long leaseMillis;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param repository репозиторий, участвующий в транзакциях сервиса
     * @param codec строгий разбор и сериализация протокола
     * @param clock общие UTC-часы приложения
     * @param leaseMillis длительность захвата в миллисекундах
     */
    public DeliveryService(DeliveryRepository repository, DeliveryCodec codec, Clock clock,
                         @Value("${warehouse.lease-ms:30000}") long leaseMillis) {
        this.repository = repository;
        this.codec = codec;
        this.clock = clock;
        this.leaseMillis = leaseMillis;
    }

    /**
     * Сохраняет поставку или диагностику raw в одной транзакции до подтверждения Kafka. Короткая блокировка
     * приёмки сериализует проверку eventId/deliveryId; одинаковый повтор не создаёт вторую поставку.
     *
     * @param topic имя топика входного сообщения
     * @param partition номер раздела Kafka
     * @param offset смещение сообщения Kafka
     * @param key ключ Kafka, обязанный совпадать с storeId
     * @param raw исходный JSON без изменения содержимого и идентификаторов
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
     * Сохраняет невалидный raw один раз на координаты topic/partition/offset в текущей транзакции; сбой записи
     * распространяется потребителю.
     *
     * @param topic имя топика входного сообщения
     * @param partition номер раздела Kafka
     * @param offset смещение сообщения Kafka
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     */
    private void diagnostic(String topic, int partition, long offset, String raw, String code, String message) {
        repository.insertDiagnostic(topic, partition, offset, timestamp(clock.instant()), code, message, raw);
    }

    /**
     * Читает поставку delivery магазина store в REPEATABLE_READ; REJECTED возвращает исходный payload.
     * Отсутствие вызывает NOT_FOUND.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View view(String store, String delivery) {
        List<View> rows = repository.views(store, delivery);
        if (rows.isEmpty()) throw new DeliveryException(404, "NOT_FOUND", "Delivery not found");
        return rows.getFirst();
    }

    /**
     * В транзакции ускоряет только WAITING_PRICING для store/delivery, сохраняя захват активного обработчика;
     * другое состояние вызывает 409.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки
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
     * В короткой транзакции захватывает наступившую поставку через SKIP LOCKED и фиксирует число попыток до
     * HTTP. Токен защищает от конкурентного обработчика.
     *
     * @return найденное значение или пустой результат при отсутствии
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
     * Продлевает захват work перед HTTP-запросом строки; false означает потерю токена и требует прекращения
     * попытки.
     *
     * @param work захваченная работа с токеном владельца
     * @return признак успешной операции согласно проверке выше
     */
    @Transactional
    public boolean renew(PricingWork work) {
        return repository.renewPricing(timestamp(clock.instant().plusMillis(leaseMillis)), work.storeId(), work.deliveryId(), work.token()) == 1;
    }

    /**
     * В одной транзакции сохраняет все priced, POSTED и единственный GoodsPosted в outbox. Токен work
     * проверяется под блокировкой; устаревший обработчик возвращает false без записи.
     *
     * @param work захваченная работа с токеном владельца
     * @param priced полный рассчитанный набор строк поставки
     * @return признак успешной операции согласно проверке выше
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
     * Сохраняет failure и ограниченную задержку только для текущего work; частичные цены не записывает,
     * следующий запуск восстанавливает ожидание.
     *
     * @param work захваченная работа с токеном владельца
     * @param failure ошибка текущей попытки или её сериализованное описание
     */
    @Transactional
    public void failedPricing(PricingWork work, Failure failure) {
        repository.schedulePricingRetry(codec.json(failure), timestamp(clock.instant().plusMillis(backoff(work.attemptCount()))), work.storeId(), work.deliveryId(), work.token());
    }

    /**
     * В короткой транзакции захватывает одно PENDING-событие; SQL-блокировки освобождаются до Kafka.
     * Просроченный захват восстанавливается после перезапуска.
     *
     * @return найденное значение или пустой результат при отсутствии
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
     * Фиксирует подтверждение брокера только для текущего токена work; неизменяемое содержимое остаётся
     * безопасным для повтора.
     *
     * @param work захваченная работа с токеном владельца
     */
    @Transactional
    public void published(OutboxWork work) {
        repository.markPublished(timestamp(clock.instant()), work.eventId(), work.token());
    }

    /**
     * Сохраняет ожидание повторной отправки work и освобождает захват; неопределённое подтверждение допускает
     * физический дубль события.
     *
     * @param work захваченная работа с токеном владельца
     */
    @Transactional
    public void failedSend(OutboxWork work) {
        repository.scheduleSendRetry(timestamp(clock.instant().plusMillis(backoff(work.attemptCount()))), work.eventId(), work.token());
    }

    /**
     * Возвращает ограниченную экспоненциальную задержку в миллисекундах по номеру attempt, максимум 60000.
     *
     * @param attempt номер попытки, начиная с первой
     */
    private long backoff(long attempt) { return Math.min(60000L, 1000L << Math.min(6L, Math.max(0L, attempt - 1))); }

    /**
     * Преобразует nullable Instant value в JDBC Timestamp без интерпретации локального часового пояса.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

}
