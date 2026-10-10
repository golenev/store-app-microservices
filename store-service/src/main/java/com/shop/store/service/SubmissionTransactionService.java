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

/** Управляет короткими транзакциями оформления и outbox; обращения к Kafka выполняются после их завершения. */
@Service
public class SubmissionTransactionService {
    private final SubmissionRepository repository;
    private final ShopCodec codec;
    private final Clock clock;
    private final long leaseMs;

    /**
     * Получает репозиторий, сериализацию, часы и leaseMs; срок захвата менее 10000 мс отклоняет при запуске.
     *
     * @param repository репозиторий, участвующий в транзакциях сервиса
     * @param codec строгий разбор и сериализация протокола
     * @param clock общие UTC-часы приложения
     * @param leaseMs длительность захвата в миллисекундах
     */
    public SubmissionTransactionService(SubmissionRepository repository,ShopCodec codec,Clock clock,@Value("${store.lease-ms:30000}") long leaseMs) {
        if(leaseMs<10000) throw new IllegalArgumentException("store.lease-ms must be at least 10000");
        this.repository=repository; this.codec=codec; this.clock=clock; this.leaseMs=leaseMs;
    }

    /**
     * Оформляет запрос в REQUIRES_NEW: блокирует корзину и остатки в порядке UUID, проверяет данные, сохраняет
     * заявку, списание, расходы, outbox и закрытие корзины атомарно. Прежний принятый ключ проверяет до
     * состояния корзины. Конфликт UNIQUE откатывает все записи до восстановления результата фасадом.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param key регистрозависимый ключ операции внутри магазина
     * @param version версия правила или корзины согласно операции
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
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
     * Читает результат конкурентного запроса по store/key в новой транзакции после отката проигравшей;
     * fingerprint обязан совпадать.
     *
     * @param store идентификатор магазина
     * @param key регистрозависимый ключ операции внутри магазина
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @return найденное значение или пустой результат при отсутствии
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<Submission> replay(String store,String key,String fingerprint) { return existing(store,key,fingerprint); }

    /**
     * Возвращает операцию по store/key или пустой результат; несовпадение fingerprint вызывает
     * IDEMPOTENCY_KEY_REUSED даже для закрытой корзины.
     *
     * @param store идентификатор магазина
     * @param key регистрозависимый ключ операции внутри магазина
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     * @return найденное значение или пустой результат при отсутствии
     */
    private Optional<Submission> existing(String store,String key,String fingerprint) {
        List<Map<String,Object>> found=repository.findByKey(store, key);
        if(found.isEmpty()) return Optional.empty();
        if(!fingerprint.equals(found.getFirst().get("request_fingerprint")))
            throw new ShopException(409,"IDEMPOTENCY_KEY_REUSED","Idempotency-Key belongs to a different request");
        return Optional.of(view(store,(UUID)found.getFirst().get("submission_id")));
    }

    /**
     * Читает операцию id магазина store и статус публикации одним SQL-запросом. Отсутствие вызывает NOT_FOUND;
     * PUBLISHED не означает оплату.
     *
     * @param store идентификатор магазина
     * @param id UUID запрашиваемого объекта
     */
    @Transactional(readOnly=true)
    public Submission view(String store,UUID id) {
        List<Submission> rows=repository.views(store, id);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Submission not found"); return rows.getFirst();
    }

    /**
     * В короткой транзакции захватывает одно наступившее событие через SKIP LOCKED. Сохраняет токен и срок
     * захвата; перезапуск допускает повтор просроченного события без нового списания.
     *
     * @return найденное значение или пустой результат при отсутствии
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
     * В транзакции фиксирует подтверждение брокера только для текущего токена work; устаревший отправитель не
     * меняет новое состояние.
     *
     * @param work захваченная работа с токеном владельца
     */
    @Transactional
    public void published(OutboxWork work) {
        repository.markPublished(Timestamp.from(clock.instant()), work.eventId(), work.leaseToken());
    }

    /**
     * Сохраняет PENDING и повтор через 1/2/4/8/16/32/60 секунд для текущего work; остатки, корзина и заявка не
     * меняются.
     *
     * @param work захваченная работа с токеном владельца
     */
    @Transactional
    public void failedSend(OutboxWork work) {
        long delay=Math.min(60,1L<<Math.min(6,Math.max(0,work.attemptCount()-1)));
        repository.scheduleSendRetry(Timestamp.from(clock.instant().plusSeconds(delay)), work.eventId(), work.leaseToken());
    }
}
