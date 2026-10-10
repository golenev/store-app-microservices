package com.shop.store.repository;

import com.shop.store.dto.CartLine;

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
 * Читает и сохраняет корзины и их позиции в PostgreSQL. Записи и блокировки участвуют в транзакции
 * вызывающего сервиса.
 */
@Repository
public class CartRepository {
    private final EntityManager entities;
    private final CartJpaRepository carts;
    private final CartItemJpaRepository items;
    private final SubmissionJpaRepository submissions;

    /**
     * Подключает Spring Data JPA для корзин, позиций и сохранённых снимков.
     * @param entities контекст для строгой вставки новой корзины без merge существующего UUID
     * @param carts корзины и блокировки
     * @param items позиции и JPQL-проекция текущих цен
     * @param submissions сохранённые снимки оформления
     */
    public CartRepository(EntityManager entities, CartJpaRepository carts, CartItemJpaRepository items, SubmissionJpaRepository submissions) {
        this.entities = entities;
        this.carts = carts;
        this.items = items;
        this.submissions = submissions;
    }

    /**
     * Сохраняет пустую корзину магазина. Повторный UUID отклоняется ограничением уникальности БД; запись
     * участвует в транзакции сервиса.
     *
     * @param cart UUID корзины
     * @param store идентификатор магазина
     * @param createdAt время создания корзины
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int insertCart(UUID cart, String store, Timestamp createdAt) {
        entities.persist(CartEntity.builder().cartId(cart).storeId(store).createdAt(createdAt.toInstant())
                .state("OPEN").version(0).build());
        entities.flush();
        return 1;
    }

    /**
     * Возвращает сохранённые JSON-снимки оформления указанной корзины. Пустой список означает, что снимка нет.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return JSON-снимки оформления корзины; пустой список, если снимка нет
     */
    public List<String> snapshots(String store, UUID cart) {
        return submissions.snapshots(store, cart);
    }

    /**
     * Читает позиции корзины с текущими ценами и вычисляет сумму каждой позиции. Возвращает не более 1001
     * строки: дополнительная строка позволяет сервису обнаружить превышение лимита в 1000 позиций.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return позиции корзины с текущими ценами и суммами; для пустой корзины список пуст
     */
    public List<CartLine> lines(String store, UUID cart) {
        return items.lines(store, cart, PageRequest.of(0, 1001)).stream().map(line ->
                new CartLine(line.stockItemId(), line.productId(), line.shortName(), line.quantity(),
                        line.unitPrice().toPlainString(), line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).toPlainString()))
                .toList();
    }

    /**
     * Сохраняет итоговое количество товара в корзине. Если позиция уже есть, заменяет её количество; остаток
     * товара не меняется.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка магазина
     * @param quantity новое итоговое количество товара в корзине
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int putItem(String store, UUID cart, UUID stock, int quantity) {
        CartItemEntity entity = items.findById(new CartItemId(cart, stock))
                .orElseGet(() -> CartItemEntity.builder().cartId(cart).stockItemId(stock).storeId(store).build());
        entity.setQuantity(quantity);
        items.save(entity);
        return 1;
    }

    /**
     * Удаляет выбранную позицию корзины. Если позиции нет, возвращает 0 и ничего не меняет.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка магазина
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int deleteItem(String store, UUID cart, UUID stock) {
        var existing = items.findById(new CartItemId(cart, stock)).filter(item -> item.getStoreId().equals(store));
        if (existing.isEmpty()) return 0;
        items.delete(existing.get());
        return 1;
    }

    /**
     * Читает состояние и версию корзины. При включённой блокировке другие изменения этой корзины ждут
     * завершения транзакции сервиса; пустой список означает отсутствие корзины.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param lock нужно ли заблокировать строку до завершения текущей транзакции
     * @return состояние и версия корзины; пустой список, если корзина отсутствует
     */
    public List<CartHeader> headers(String store, UUID cart, boolean lock) {
        var found = lock ? carts.lockCart(store, cart) : carts.findByStoreIdAndCartId(store, cart);
        return found.map(entity -> List.of(new CartHeader(entity.getState(), entity.getVersion()))).orElseGet(List::of);
    }

    /**
     * Увеличивает версию корзины на один. Изменение сохраняется или отменяется вместе с транзакцией сервиса.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return число изменённых строк; 0, если запись не найдена или условие изменения не выполнено
     */
    public int advance(String store, UUID cart) {
        var found = carts.findByStoreIdAndCartId(store, cart);
        if (found.isEmpty()) return 0;
        CartEntity entity = found.get();
        entity.setVersion(entity.getVersion() + 1);
        return 1;
    }
}
