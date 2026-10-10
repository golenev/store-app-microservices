package com.shop.store.service;

import com.shop.store.codec.ShopCodec;
import com.shop.store.dto.Cart;
import com.shop.store.dto.CartLine;
import com.shop.store.dto.Catalog;
import com.shop.store.dto.PutItem;
import com.shop.store.dto.Stock;
import com.shop.store.exception.ShopException;
import com.shop.store.repository.CartRepository;
import com.shop.store.repository.InventoryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/** Управляет независимыми версионными корзинами и транзакциями; добавление не резервирует и не списывает товар. */
@Service
public class CartService {
    private final CartRepository repository;
    private final InventoryRepository inventory;
    private final Clock clock;
    private final ShopCodec codec;
    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param repository репозиторий, участвующий в транзакциях сервиса
     * @param inventory доступ к общим остаткам магазина
     * @param clock общие UTC-часы приложения
     * @param codec строгий разбор и сериализация протокола
     */
    public CartService(CartRepository repository,InventoryRepository inventory,Clock clock,ShopCodec codec) { this.repository=repository; this.inventory=inventory; this.clock=clock; this.codec=codec; }
    /**
     * Возвращает каталог магазина store со стабильными идентификаторами и нулевыми остатками в транзакции
     * чтения.
     *
     * @param store идентификатор магазина
     */
    @Transactional(readOnly=true)
    public Catalog catalog(String store) { return inventory.catalog(store); }
    /**
     * Создаёт независимую пустую OPEN-корзину магазина store с версией 0 и возвращает её после записи;
     * неизвестный магазин отклоняет.
     *
     * @param store идентификатор магазина
     */
    @Transactional
    public Cart create(String store) {
        inventory.requireStore(store); UUID cart=UUID.randomUUID();
        repository.insertCart(cart, store, Timestamp.from(clock.instant()));
        return get(store,cart);
    }
    /**
     * Читает корзину cart магазина store в REPEATABLE_READ. OPEN использует текущие цены; SUBMITTED возвращает
     * неизменяемый снимок оформления.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Cart get(String store,UUID cart) {
        Map<String,Object> header=header(store,cart,false);
        if(!header.get("state").equals("OPEN")) {
            List<String> snapshots=repository.snapshots(store, cart);
            if(snapshots.isEmpty()) throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart snapshot unavailable");
            return codec.cartSnapshot(snapshots.getFirst());
        }
        List<CartLine> lines=repository.lines(store, cart);
        BigDecimal total=lines.stream().map(line -> new BigDecimal(line.lineTotal())).reduce(new BigDecimal("0.00"),BigDecimal::add);
        if(lines.size()>1000 || !total.toPlainString().matches("(0|[1-9][0-9]{0,35})\\.[0-9]{2}"))
            throw new ShopException(503,"DEPENDENCY_UNAVAILABLE","Cart amount or size exceeds the v1 response limit");
        return new Cart(store,cart,((Number)header.get("version")).longValue(),"OPEN",lines,total.toPlainString(),"RUB",null);
    }
    /**
     * В одной транзакции блокирует корзину, затем остаток stock. Проверяет ожидаемую версию и заменяет итоговое
     * количество из input; товар не резервирует. Ошибка откатывает состав и версию.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка
     * @param input проверяемые параметры операции
     */
    @Transactional
    public Cart put(String store,UUID cart,UUID stock,PutItem input) {
        if(input==null || input.quantity()<1 || input.expectedCartVersion()<0 || input.expectedCartVersion()>ShopCodec.MAX_VERSION)
            throw new ShopException(400,"VALIDATION_ERROR","Invalid cart quantity or version");
        editable(header(store,cart,true),input.expectedCartVersion());
        Stock current=inventory.lockStock(store,stock);
        if(input.quantity()>current.availableQuantity()) throw new ShopException(409,"INSUFFICIENT_STOCK","Requested quantity exceeds current stock");
        repository.putItem(store, cart, stock, input.quantity());
        advance(store,cart);
        return mutationView(store,cart);
    }
    /**
     * Удаляет существующую позицию stock под блокировкой корзины и проверкой expected. Возвращает обновлённую
     * корзину; ошибка сохраняет прежние состав и версию.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка
     * @param expected ожидаемая версия корзины
     */
    @Transactional
    public Cart delete(String store,UUID cart,UUID stock,long expected) {
        editable(header(store,cart,true),expected);
        if(repository.deleteItem(store, cart, stock)==0)
            throw new ShopException(404,"NOT_FOUND","Cart item not found");
        advance(store,cart); return mutationView(store,cart);
    }
    /**
     * Читает состояние и версию корзины магазина; lock включает блокировку до конца текущей транзакции.
     * Отсутствие корзины вызывает NOT_FOUND.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param lock нужна ли блокировка строки до конца текущей транзакции
     */
    private Map<String,Object> header(String store,UUID cart,boolean lock) {
        List<Map<String,Object>> rows=repository.headers(store, cart, lock);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Cart not found"); return rows.getFirst();
    }
    /**
     * Требует OPEN и совпадение версии header с expected. Закрытая, изменённая или исчерпавшая версию корзина
     * отклоняется до записи.
     *
     * @param header состояние и версия прочитанной корзины
     * @param expected ожидаемая версия корзины
     */
    private void editable(Map<String,Object> header,long expected) {
        if(expected<0 || expected>ShopCodec.MAX_VERSION) throw new ShopException(400,"VALIDATION_ERROR","Invalid expectedCartVersion");
        if(!header.get("state").equals("OPEN")) throw new ShopException(409,"CART_ALREADY_SUBMITTED","Cart is already submitted");
        long version=((Number)header.get("version")).longValue();
        if(version!=expected) throw new ShopException(409,"CART_VERSION_CONFLICT","Cart version has changed");
        if(version==ShopCodec.MAX_VERSION) throw new ShopException(400,"VALIDATION_ERROR","Cart version is exhausted");
    }
    /**
     * Увеличивает версию корзины магазина один раз, включая повтор PUT с тем же количеством; откат транзакции
     * отменяет изменение.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     */
    private void advance(String store,UUID cart) { repository.advance(store, cart); }
    /**
     * Строит изменённую корзину внутри текущей транзакции; непредставимую сумму превращает в VALIDATION_ERROR
     * для отката всей операции.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     */
    private Cart mutationView(String store,UUID cart) {
        try { return get(store,cart); }
        catch(ShopException failure) {
            if(failure.status()==503) throw new ShopException(400,"VALIDATION_ERROR","Cart amount exceeds the v1 money limit");
            throw failure;
        }
    }
}
