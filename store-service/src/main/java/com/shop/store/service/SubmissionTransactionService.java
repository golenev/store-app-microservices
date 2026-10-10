package com.shop.store.service;

import com.shop.store.codec.ShopCodec;
import com.shop.store.dto.Cart;
import com.shop.store.dto.CartLine;
import com.shop.store.dto.Submission;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.OrderEvent;
import com.shop.store.messaging.dto.OrderPayload;
import com.shop.store.model.OutboxWork;
import com.shop.store.repository.SubmissionRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/**
 * Сохраняет оформление корзины и очередь исходящих событий в БД. Каждый публичный метод выполняет свою
 * транзакционную операцию; отправка в Kafka проходит после освобождения блокировок.
 */
@Service
public class SubmissionTransactionService {
    private final SubmissionRepository repository;
    private final ShopCodec codec;
    private final Clock clock;
    private final long leaseMs;

    /**
     * Подключает хранение оформления, JSON и часы и задаёт срок владения исходящим событием. Значение меньше
     * 10 000 миллисекунд отклоняет при создании сервиса.
     *
     * @param repository хранение заявок, списаний и очереди событий
     * @param codec проверка входного JSON магазина и преобразование его моделей
     * @param clock часы для дат операций и сроков фоновых попыток
     * @param leaseMs срок владения событием в миллисекундах; не менее 10 000
     */
    public SubmissionTransactionService(SubmissionRepository repository,ShopCodec codec,Clock clock,@Value("${store.lease-ms:30000}") long leaseMs) {
        if(leaseMs<10000) throw new IllegalArgumentException("store.lease-ms must be at least 10000");
        this.repository=repository; this.codec=codec; this.clock=clock; this.leaseMs=leaseMs;
    }

    /**
     * Оформляет корзину в новой транзакции и возвращает принятую операцию. Повтор с тем же ключом и данными
     * возвращает прежний результат. Для нового запроса блокирует корзину и остатки в порядке UUID, проверяет
     * версию и доступное количество. Заявка, списание, движения, событие в очереди отправки и закрытие корзины
     * сохраняются вместе; любая ошибка отменяет все записи. Конфликт уникального ключа передаёт вызывающему
     * сервису, который прочитает результат принятого запроса после отмены этой транзакции.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param key ключ распознавания повторного оформления внутри магазина
     * @param version ожидаемая версия корзины до оформления
     * @param fingerprint контрольная сумма магазина, корзины и ожидаемой версии запроса оформления
     * @return принятая заявка и состояние отправки её события
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Submission accept(String store,UUID cart,String key,long version,String fingerprint) {
        Optional<Submission> prior=existing(store,key,fingerprint);
        if(prior.isPresent()) return prior.get();
        List<Map<String,Object>> headers=repository.lockCart(store, cart);
        if(headers.isEmpty()) throw new ShopException(404,"NOT_FOUND","Cart not found");
        prior=existing(store,key,fingerprint);
        if(prior.isPresent()) return prior.get();
        Map<String,Object> header=headers.getFirst();
        if(!header.get("state").equals("OPEN")) throw new ShopException(409,"CART_ALREADY_SUBMITTED","Cart is already submitted");
        if(((Number)header.get("version")).longValue()!=version) throw new ShopException(409,"CART_VERSION_CONFLICT","Cart version has changed");
        if(version==ShopCodec.MAX_VERSION) throw new ShopException(400,"VALIDATION_ERROR","Cart version is exhausted");
        List<Map<String,Object>> rows=repository.lockStockLines(store, cart);
        // Запрос другой корзины мог зафиксироваться, пока эта операция ожидала блокировки остатков.
        prior=existing(store,key,fingerprint);
        if(prior.isPresent()) return prior.get();
        if(rows.isEmpty() || rows.size()>1000) throw new ShopException(400,"VALIDATION_ERROR","Cart must contain 1 to 1000 items");
        List<CartLine> lines=new ArrayList<>(); BigDecimal total=new BigDecimal("0.00");
        for(Map<String,Object> row:rows) {
            int quantity=((Number)row.get("quantity")).intValue();
            if(quantity>((Number)row.get("available_quantity")).intValue())
                throw new ShopException(409,"INSUFFICIENT_STOCK","Requested quantity exceeds current stock");
            BigDecimal price=(BigDecimal)row.get("unit_price"), amount=price.multiply(BigDecimal.valueOf(quantity));
            lines.add(new CartLine((UUID)row.get("stock_item_id"),(String)row.get("product_id"),(String)row.get("short_name"),
                    quantity,price.toPlainString(),amount.toPlainString()));
            total=total.add(amount);
        }
        if(!total.toPlainString().matches("(0|[1-9][0-9]{0,35})\\.[0-9]{2}"))
            throw new ShopException(400,"VALIDATION_ERROR","Cart amount exceeds the v1 money limit");
        UUID submission=UUID.randomUUID(),event=UUID.randomUUID(); var accepted=clock.instant();
        Cart snapshot=new Cart(store,cart,version+1,"SUBMITTED",List.copyOf(lines),total.toPlainString(),"RUB",submission);
        OrderPayload payload=new OrderPayload(submission,cart,accepted,snapshot.items(),snapshot.totalAmount(),"RUB");
        String eventJson=codec.json(new OrderEvent(event,"OrderSubmitted",1,accepted,store,payload));
        repository.insertSubmission(submission, store, cart, key, fingerprint, version, event, Timestamp.from(accepted), codec.json(snapshot));
        for(CartLine line:lines) {
            repository.deductStock(line.quantity(), store, line.stockItemId());
            repository.insertExpense(store, submission, line.stockItemId(), line.quantity(), new BigDecimal(line.unitPrice()), Timestamp.from(accepted));
        }
        repository.insertOutbox(event, submission, store, eventJson, Timestamp.from(accepted));
        repository.closeCart(store, cart);
        return view(store,submission);
    }

    /**
     * Ищет принятую операцию по магазину и ключу в новой транзакции чтения. Используется после отмены
     * одновременного запроса. При совпадении контрольной суммы возвращает прежний результат; другой запрос с
     * тем же ключом вызывает {@code IDEMPOTENCY_KEY_REUSED}.
     *
     * @param store идентификатор магазина
     * @param key ключ распознавания повторного оформления внутри магазина
     * @param fingerprint контрольная сумма магазина, корзины и ожидаемой версии запроса оформления
     * @return прежняя заявка или пустой результат, если ключ не использован
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<Submission> replay(String store,String key,String fingerprint) { return existing(store,key,fingerprint); }

    /**
     * Ищет прежнее оформление по ключу внутри магазина. Если ключ свободен, возвращает пустой результат; если
     * данные отличаются, выдаёт {@code IDEMPOTENCY_KEY_REUSED}, даже когда корзина уже закрыта.
     *
     * @param store идентификатор магазина
     * @param key ключ распознавания повторного оформления внутри магазина
     * @param fingerprint контрольная сумма магазина, корзины и ожидаемой версии запроса оформления
     * @return прежняя заявка или пустой результат, если ключ не использован
     */
    private Optional<Submission> existing(String store,String key,String fingerprint) {
        List<Map<String,Object>> found=repository.findByKey(store, key);
        if(found.isEmpty()) return Optional.empty();
        if(!fingerprint.equals(found.getFirst().get("request_fingerprint")))
            throw new ShopException(409,"IDEMPOTENCY_KEY_REUSED","Idempotency-Key belongs to a different request");
        return Optional.of(view(store,(UUID)found.getFirst().get("submission_id")));
    }

    /**
     * Возвращает принятую операцию и состояние отправки её события. Отсутствие операции вызывает {@code
     * NOT_FOUND}. Статус {@code PUBLISHED} означает подтверждение Kafka и не подтверждает оплату.
     *
     * @param store идентификатор магазина
     * @param id UUID принятой заявки
     * @return принятая заявка и состояние отправки её события
     */
    @Transactional(readOnly=true)
    public Submission view(String store,UUID id) {
        List<Submission> rows=repository.views(store, id);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Submission not found"); return rows.getFirst();
    }

    /**
     * Выбирает одно событие, для которого наступило время отправки, и временно назначает ему владельца.
     * Занятые строки пропускает без ожидания. В транзакции сохраняет идентификатор владельца, срок и номер
     * попытки; после завершения освобождает блокировку. Событие с истёкшим сроком можно взять снова после сбоя
     * или перезапуска без повторного списания товара.
     *
     * @return событие для отправки или пустой результат, если подходящего события нет
     */
    @Transactional
    public Optional<OutboxWork> claimOutbox() {
        Timestamp now=Timestamp.from(clock.instant());
        List<Map<String,Object>> rows=repository.lockDueOutbox(now, now);
        if(rows.isEmpty()) return Optional.empty(); Map<String,Object> row=rows.getFirst();
        UUID event=(UUID)row.get("event_id"),token=UUID.randomUUID();
        int attempts=(int)Math.min(Integer.MAX_VALUE,((Number)row.get("attempt_count")).longValue()+1);
        repository.claimOutbox(token, Timestamp.from(clock.instant().plusMillis(leaseMs)), attempts, event);
        return Optional.of(new OutboxWork(event,(String)row.get("store_id"),(String)row.get("payload"),token,attempts));
    }

    /**
     * После подтверждения Kafka отмечает событие как отправленное. Запись разрешена только при совпадении
     * идентификатора владельца; запоздалое подтверждение прежнего обработчика не меняет состояние.
     *
     * @param work выбранное событие, идентификатор его владельца и номер попытки
     */
    @Transactional
    public void published(OutboxWork work) {
        repository.markPublished(Timestamp.from(clock.instant()), work.eventId(), work.leaseToken());
    }

    /**
     * Оставляет событие в ожидании отправки и назначает повтор через 1, 2, 4, 8, 16, 32 или 60 секунд в
     * зависимости от номера попытки. Меняет запись только для текущего владельца; остатки и оформленная
     * корзина остаются прежними.
     *
     * @param work выбранное событие, идентификатор его владельца и номер попытки
     */
    @Transactional
    public void failedSend(OutboxWork work) {
        long delay=Math.min(60,1L<<Math.min(6,Math.max(0,work.attemptCount()-1)));
        repository.scheduleSendRetry(Timestamp.from(clock.instant().plusSeconds(delay)), work.eventId(), work.leaseToken());
    }
}
