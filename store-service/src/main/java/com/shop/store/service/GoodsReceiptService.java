package com.shop.store.service;

import com.shop.store.codec.ShopCodec;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.GoodsEvent;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.messaging.dto.PostedPayload;
import com.shop.store.model.Decoded;
import com.shop.store.model.ExistingStock;
import com.shop.store.repository.GoodsReceiptRepository;
import com.shop.store.repository.InventoryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/** Управляет атомарным приходом, защитой повторов и порядком изменения цены; SQL выполняют репозитории. */
@Service
public class GoodsReceiptService {
    private final GoodsReceiptRepository repository;
    private final InventoryRepository inventory;
    private final ShopCodec codec;
    private final Clock clock;
    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param repository репозиторий, участвующий в транзакциях сервиса
     * @param inventory доступ к общим остаткам магазина
     * @param codec строгий разбор и сериализация протокола
     * @param clock общие UTC-часы приложения
     */
    public GoodsReceiptService(GoodsReceiptRepository repository,InventoryRepository inventory,ShopCodec codec,Clock clock) {
        this.repository=repository; this.inventory=inventory; this.codec=codec; this.clock=clock;
    }
    /**
     * В одной транзакции сохраняет приход или диагностику входного raw. Сначала блокирует eventId, затем
     * магазин; одинаковый повтор не меняет остаток. Цена обновляется только по более новому порядку поставки.
     * Сбой записи распространяется потребителю до подтверждения Kafka.
     *
     * @param topic имя топика входного сообщения
     * @param partition номер раздела Kafka
     * @param offset смещение сообщения Kafka
     * @param key ключ Kafka, обязанный совпадать с storeId
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    @Transactional
    public void receive(String topic,int partition,long offset,String key,String raw) {
        Decoded decoded;
        try { decoded=codec.goods(raw); }
        catch(ShopException failure) { diagnostic(topic,partition,offset,raw,failure.code(),failure.getMessage()); return; }
        GoodsEvent event=decoded.event(); PostedPayload payload=event.payload();
        if(!event.storeId().equals(key)) { diagnostic(topic,partition,offset,raw,"VALIDATION_ERROR","Kafka key must equal storeId"); return; }
        repository.lockEvent(event.eventId().toString());
        List<String> stores=repository.lockStore(event.storeId());
        if(stores.isEmpty()) { diagnostic(topic,partition,offset,raw,"NOT_FOUND","Unknown store"); return; }
        List<Map<String,Object>> seen=repository.processedEvents(event.eventId());
        if(!seen.isEmpty()) {
            Map<String,Object> previous=seen.getFirst();
            if(!event.storeId().equals(previous.get("store_id")) || !payload.deliveryId().equals(previous.get("delivery_id"))
                    || !decoded.fingerprint().equals(previous.get("fingerprint")))
                diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","eventId has different business content");
            return;
        }
        List<String> prior=repository.receiptFingerprints(event.storeId(), payload.deliveryId());
        if(!prior.isEmpty()) {
            if(!prior.getFirst().equals(decoded.fingerprint())) {
                diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","deliveryId has different business content"); return;
            }
            remember(event,decoded.fingerprint()); return;
        }
        if(repository.sequenceCount(event.storeId(), payload.deliverySequence())!=0) {
            diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","deliverySequence belongs to another delivery"); return;
        }
        Map<String,ExistingStock> existing=inventory.lockIncoming(event.storeId(),payload.items());
        long count=repository.inventoryCount(event.storeId());
        if(count+payload.items().size()-existing.size()>1000) {
            diagnostic(topic,partition,offset,raw,"VALIDATION_ERROR","Inventory would exceed the v1 catalog limit"); return;
        }
        for(PostedLine line:payload.items()) {
            ExistingStock previous=existing.get(line.productId());
            if(previous!=null && !previous.stock().productType().equals(line.productType())) {
                diagnostic(topic,partition,offset,raw,"DELIVERY_CONTENT_CONFLICT","Product type differs from existing inventory"); return;
            }
            if(previous!=null && (long)previous.stock().availableQuantity()+line.quantity()>Integer.MAX_VALUE) {
                diagnostic(topic,partition,offset,raw,"VALIDATION_ERROR","Inventory quantity would overflow"); return;
            }
        }
        Timestamp applied=Timestamp.from(clock.instant());
        repository.insertReceipt(event.storeId(), payload.deliveryId(), payload.deliverySequence(), decoded.fingerprint(), Timestamp.from(payload.receivedAt()), Timestamp.from(payload.postedAt()), applied, codec.json(event));
        for(PostedLine line:payload.items()) {
            ExistingStock previous=existing.get(line.productId());
            UUID stock=previous==null ? UUID.randomUUID() : previous.stock().stockItemId();
            if(previous==null) repository.insertStock(stock, event.storeId(), line.productId(), line.productType(), line.shortName(), line.description(), new BigDecimal(line.salePrice()), line.currency(), line.quantity(), payload.deliverySequence());
            else if(payload.deliverySequence()>previous.sequence()) repository.addStockAndReprice(line.quantity(), new BigDecimal(line.salePrice()), line.shortName(), line.description(), payload.deliverySequence(), stock, event.storeId());
            else repository.addStock(line.quantity(), stock, event.storeId());
            repository.insertMovement(event.storeId(), payload.deliveryId(), line.lineId(), stock, line.quantity(), new BigDecimal(line.salePrice()), applied);
        }
        remember(event,decoded.fingerprint());
    }
    /**
     * Регистрирует event с fingerprint внутри транзакции прихода; дополнительное транспортное событие не
     * создаёт второе движение.
     *
     * @param event событие приёмки с проверенным содержимым
     * @param fingerprint канонический отпечаток бизнес-содержимого для проверки повтора
     */
    private void remember(GoodsEvent event,String fingerprint) {
        repository.rememberEvent(event.eventId(), event.storeId(), event.payload().deliveryId(), fingerprint);
    }
    /**
     * Сохраняет raw и безопасную ошибку один раз на координаты topic/partition/offset; сбой записи требует
     * повторной обработки сообщения.
     *
     * @param topic имя топика входного сообщения
     * @param partition номер раздела Kafka
     * @param offset смещение сообщения Kafka
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     */
    private void diagnostic(String topic,int partition,long offset,String raw,String code,String message) {
        repository.insertDiagnostic(topic, partition, offset, Timestamp.from(clock.instant()), code, message, raw);
    }
}
