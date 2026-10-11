package com.shop.store.service;

import com.shop.store.codec.ShopCodec;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.GoodsEvent;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.messaging.dto.PostedPayload;
import com.shop.store.model.Decoded;
import com.shop.store.model.ExistingStock;
import com.shop.store.model.ProcessedGoods;
import com.shop.store.repository.GoodsReceiptRepository;
import com.shop.store.repository.InventoryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/**
 * Принимает оприходованные поставки в магазин. Сохраняет остатки и движения вместе, распознаёт повторы и
 * выбирает цену по порядку приёмки поставок в WAREHOUSE.
 */
@Service
public class GoodsReceiptService {
    private final GoodsReceiptRepository repository;
    private final InventoryRepository inventory;
    private final ShopCodec codec;
    private final Clock clock;
    /**
     * Подключает хранение приходов, доступ к остаткам, проверку событий и часы для приёмки поставок.
     *
     * @param repository запись приходов, движений и обработанных событий
     * @param inventory чтение каталога и блокировка остатков магазина
     * @param codec проверка входного JSON магазина и преобразование его моделей
     * @param clock часы для дат операций и сроков фоновых попыток
     */
    public GoodsReceiptService(GoodsReceiptRepository repository,InventoryRepository inventory,ShopCodec codec,Clock clock) {
        this.repository=repository; this.inventory=inventory; this.codec=codec; this.clock=clock;
    }
    /**
     * Обрабатывает сообщение о поставке в одной транзакции. Сначала блокирует идентификатор события, затем
     * магазин и нужные остатки. Новая поставка увеличивает количество; одинаковый повтор ничего не списывает и
     * не добавляет. Цену меняет только поставка с более поздним порядком первой приёмки в WAREHOUSE. Неверные
     * данные и конфликты сохраняет для диагностики без изменения остатков. Если запись в БД не удалась,
     * передаёт ошибку потребителю Kafka для повторной обработки.
     *
     * @param topic имя канала Kafka, из которого получено сообщение
     * @param partition номер раздела канала Kafka
     * @param offset позиция сообщения внутри раздела Kafka
     * @param key ключ сообщения Kafka, который должен совпадать с идентификатором магазина
     * @param raw исходный текст JSON-сообщения
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
        List<ProcessedGoods> seen=repository.processedEvents(event.eventId());
        if(!seen.isEmpty()) {
            ProcessedGoods previous=seen.getFirst();
            if(!event.storeId().equals(previous.storeId()) || !payload.deliveryId().equals(previous.deliveryId())
                    || !decoded.fingerprint().equals(previous.fingerprint()))
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
     * Сохраняет идентификатор обработанного события и контрольную сумму поставки в текущей транзакции. Это
     * позволяет распознать следующее сообщение, даже если поставка уже была принята под другим идентификатором
     * события.
     *
     * @param event событие оприходованной поставки
     * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
     */
    private void remember(GoodsEvent event,String fingerprint) {
        repository.rememberEvent(event.eventId(), event.storeId(), event.payload().deliveryId(), fingerprint);
    }
    /**
     * Сохраняет исходное сообщение и причину отказа. Координаты сообщения в Kafka не позволяют создать вторую
     * запись при повторе; ошибка записи передаётся потребителю, чтобы сообщение не было потеряно.
     *
     * @param topic имя канала Kafka, из которого получено сообщение
     * @param partition номер раздела канала Kafka
     * @param offset позиция сообщения внутри раздела Kafka
     * @param raw исходный текст JSON-сообщения
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    private void diagnostic(String topic,int partition,long offset,String raw,String code,String message) {
        repository.insertDiagnostic(topic, partition, offset, Timestamp.from(clock.instant()), code, message, raw);
    }
}
