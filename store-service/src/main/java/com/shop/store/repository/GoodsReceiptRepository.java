package com.shop.store.repository;

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
 * Сохраняет приходы, движения и отметки об обработанных событиях в PostgreSQL. Все операции JPA и блокировки
 * входят в транзакцию сервиса приёмки.
 */
@Repository
public class GoodsReceiptRepository {
    private final EntityManager entities;
    private final StoreScopeJpaRepository stores;
    private final ReceiptJpaRepository receipts;
    private final ProcessedEventJpaRepository events;
    private final InventoryJpaRepository stocks;

    /**
     * Подключает JPA и стандартные репозитории. Новые неизменяемые записи вставляет через persist,
     * чтобы заданный идентификатор не превращал повторную вставку в merge и изменение истории.
     * @param entities контекст текущей транзакции
     * @param stores магазины
     * @param receipts принятые поставки
     * @param events обработанные события
     * @param stocks остатки
     */
    public GoodsReceiptRepository(EntityManager entities, StoreScopeJpaRepository stores, ReceiptJpaRepository receipts, ProcessedEventJpaRepository events, InventoryJpaRepository stocks) {
        this.entities = entities;
        this.stores = stores;
        this.receipts = receipts;
        this.events = events;
        this.stocks = stocks;
    }

    /**
     * Блокирует обработку данного идентификатора события до конца транзакции. Одновременный повтор ждёт её
     * завершения и затем может проверить сохранённый результат.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return значение 1 после получения блокировки события
     */
    public Integer lockEvent(String eventId) {
        entities.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(731005,hashtext(:event))")
                .setParameter("event", eventId).getSingleResult();
        return 1;
    }

    /**
     * Блокирует строку магазина до конца транзакции, чтобы его поставки применялись последовательно. Пустой
     * список означает неизвестный магазин.
     *
     * @param store идентификатор магазина
     * @return идентификатор найденного магазина или пустой список для неизвестного магазина
     */
    public List<String> lockStore(String store) {
        return stores.lockStore(store).map(entity -> List.of(entity.getStoreId())).orElseGet(List::of);
    }

    /**
     * Возвращает сохранённые магазин, поставку и контрольную сумму обработанного события. Пустой список
     * означает, что идентификатор события ещё не зарегистрирован.
     *
     * @param eventId идентификатор события для распознавания повторного сообщения
     * @return сохранённые сведения об обработанном событии или пустой список
     */
    public List<ProcessedGoods> processedEvents(UUID eventId) {
        return events.findById(eventId).map(entity -> List.of(new ProcessedGoods(entity.getStoreId(),
                entity.getDeliveryId(), entity.getFingerprint()))).orElseGet(List::of);
    }

    /**
     * Возвращает контрольную сумму уже принятой поставки этого магазина. По ней сервис проверяет, совпадает ли
     * содержимое повторного сообщения; пустой список означает отсутствие поставки.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @return контрольная сумма принятой поставки или пустой список, если её нет
     */
    public List<String> receiptFingerprints(String store, String delivery) {
        return receipts.findById(new ReceiptId(store, delivery)).map(entity -> List.of(entity.getFingerprint())).orElseGet(List::of);
    }

    /**
     * Считает поставки магазина с заданным порядковым номером первой приёмки. Ненулевой результат позволяет
     * сервису обнаружить повторное использование номера другой поставкой.
     *
     * @param store идентификатор магазина
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @return число поставок магазина с указанным порядковым номером
     */
    public Integer sequenceCount(String store, long sequence) {
        return Math.toIntExact(receipts.countByStoreIdAndDeliverySequence(store, sequence));
    }

    /**
     * Возвращает количество разных товаров в остатках магазина для проверки лимита каталога.
     *
     * @param store идентификатор магазина
     * @return число разных товаров в остатках магазина
     */
    public Long inventoryCount(String store) {
        return stocks.countByStoreId(store);
    }

    /**
     * Сохраняет принятую поставку, её контрольную сумму, даты и исходное событие. Запись выполняется вместе с
     * изменением остатков в транзакции сервиса.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
     * @param receivedAt время первой приёмки поставки в WAREHOUSE
     * @param postedAt время завершения расчёта и оприходования поставки в WAREHOUSE
     * @param appliedAt время применения прихода к остаткам магазина
     * @param payload JSON принятого события поставки для хранения истории прихода
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertReceipt(String store, String delivery, long sequence, String fingerprint, Timestamp receivedAt, Timestamp postedAt, Timestamp appliedAt, String payload) {
        entities.persist(ReceiptEntity.builder().storeId(store).deliveryId(delivery).deliverySequence(sequence)
                .fingerprint(fingerprint).receivedAt(receivedAt.toInstant()).postedAt(postedAt.toInstant())
                .appliedAt(appliedAt.toInstant()).payload(payload).build());
        entities.flush();
        return 1;
    }

    /**
     * Создаёт остаток для первого прихода продукта в магазин. БД запрещает вторую позицию с той же парой
     * магазина и продукта.
     *
     * @param stock UUID позиции остатка магазина
     * @param store идентификатор магазина
     * @param product идентификатор продукта
     * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
     * @param shortName краткое название товара
     * @param description описание товара
     * @param price продажная цена как точное десятичное число
     * @param currency код валюты; в текущем контракте разрешён {@code RUB}
     * @param quantity количество единиц товара в этом приходе
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertStock(UUID stock, String store, String product, String productType, String shortName, String description, BigDecimal price, String currency, int quantity, long sequence) {
        entities.persist(InventoryEntity.builder().stockItemId(stock).storeId(store).productId(product)
                .productType(productType).shortName(shortName).description(description).unitPrice(price)
                .currency(currency).availableQuantity(quantity).lastDeliverySequence(sequence).build());
        entities.flush();
        return 1;
    }

    /**
     * Увеличивает остаток и заменяет цену, название, описание и номер поставки, определившей цену. Сервис
     * должен заранее проверить, что эта поставка новее прежней; все изменения входят в его транзакцию.
     *
     * @param quantity количество единиц товара в этом приходе
     * @param price продажная цена как точное десятичное число
     * @param shortName краткое название товара
     * @param description описание товара
     * @param sequence неизменяемый номер первой приёмки поставки в WAREHOUSE для магазина
     * @param stock UUID позиции остатка магазина
     * @param store идентификатор магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int addStockAndReprice(int quantity, BigDecimal price, String shortName, String description, long sequence, UUID stock, String store) {
        InventoryEntity entity = entities.find(InventoryEntity.class, stock);
        if (entity == null || !entity.getStoreId().equals(store)) return 0;
        entity.setAvailableQuantity(entity.getAvailableQuantity() + quantity);
        entity.setUnitPrice(price);
        entity.setShortName(shortName);
        entity.setDescription(description);
        entity.setLastDeliverySequence(sequence);
        return 1;
    }

    /**
     * Увеличивает остаток по более старой поставке. Сохраняет цену и описание, которые уже задала более новая
     * поставка.
     *
     * @param quantity количество единиц товара в этом приходе
     * @param stock UUID позиции остатка магазина
     * @param store идентификатор магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int addStock(int quantity, UUID stock, String store) {
        InventoryEntity entity = entities.find(InventoryEntity.class, stock);
        if (entity == null || !entity.getStoreId().equals(store)) return 0;
        entity.setAvailableQuantity(entity.getAvailableQuantity() + quantity);
        return 1;
    }

    /**
     * Сохраняет движение прихода по строке поставки. Движение и изменение остатка сохраняются или отменяются
     * вместе.
     *
     * @param store идентификатор магазина
     * @param delivery идентификатор поставки внутри магазина
     * @param lineId идентификатор строки внутри поставки
     * @param stock UUID позиции остатка магазина
     * @param quantity количество единиц товара в этом приходе
     * @param price продажная цена как точное десятичное число
     * @param appliedAt время применения прихода к остаткам магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertMovement(String store, String delivery, String lineId, UUID stock, int quantity, BigDecimal price, Timestamp appliedAt) {
        entities.persist(StockMovementEntity.builder().storeId(store).deliveryId(delivery).lineId(lineId)
                .stockItemId(stock).quantity(quantity).unitPrice(price).appliedAt(appliedAt.toInstant()).build());
        entities.flush();
        return 1;
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
        entities.persist(ProcessedEventEntity.builder().eventId(eventId).storeId(store).deliveryId(delivery)
                .fingerprint(fingerprint).build());
        entities.flush();
        return 1;
    }

    /**
     * Сохраняет сообщение и причину отказа по его координатам в Kafka. Повтор этих координат не создаёт новую
     * запись; ошибка БД требует повторной обработки сообщения.
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
        // ON CONFLICT сохраняет атомарную дедупликацию координат, которой нет у save/merge.
        entities.flush();
        return entities.createNativeQuery("""
                INSERT INTO incoming_goods_diagnostics(topic,partition_id,kafka_offset,recorded_at,code,message,raw_message)
                VALUES(:topic,:partition,:offset,:recordedAt,:code,:message,:raw)
                ON CONFLICT(topic,partition_id,kafka_offset) DO NOTHING
                """).setParameter("topic", topic).setParameter("partition", partition).setParameter("offset", offset)
                .setParameter("recordedAt", recordedAt.toInstant()).setParameter("code", code)
                .setParameter("message", message).setParameter("raw", raw).executeUpdate();
    }
}
