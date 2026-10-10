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

/**
 * Создаёт и изменяет отдельные корзины покупателей. Проверяет версии корзин и доступное количество товара;
 * добавление в корзину не резервирует остаток.
 */
@Service
public class CartService {
    private final CartRepository repository;
    private final InventoryRepository inventory;
    private final Clock clock;
    private final ShopCodec codec;
    /**
     * Подключает хранение корзин, чтение остатков, часы и преобразование JSON для операций с корзинами.
     *
     * @param repository чтение и запись корзин в PostgreSQL
     * @param inventory чтение каталога и блокировка остатков магазина
     * @param clock часы для дат операций и сроков фоновых попыток
     * @param codec проверка входного JSON магазина и преобразование его моделей
     */
    public CartService(CartRepository repository,InventoryRepository inventory,Clock clock,ShopCodec codec) { this.repository=repository; this.inventory=inventory; this.clock=clock; this.codec=codec; }
    /**
     * Возвращает каталог указанного магазина, включая товары с нулевым остатком. Читает данные в транзакции
     * без записей.
     *
     * @param store идентификатор магазина
     * @return каталог указанного магазина с текущими ценами и остатками
     */
    @Transactional(readOnly=true)
    public Catalog catalog(String store) { return inventory.catalog(store); }
    /**
     * Создаёт пустую корзину со статусом {@code OPEN} и версией 0 и возвращает её состояние. Запись
     * выполняется в одной транзакции; неизвестный магазин отклоняет с {@code NOT_FOUND}.
     *
     * @param store идентификатор магазина
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    @Transactional
    public Cart create(String store) {
        inventory.requireStore(store); UUID cart=UUID.randomUUID();
        repository.insertCart(cart, store, Timestamp.from(clock.instant()));
        return get(store,cart);
    }
    /**
     * Возвращает корзину и её сумму. Для открытой корзины берёт текущие цены; для оформленной — сохранённый
     * при оформлении снимок. Повторяемое чтение в одной транзакции сохраняет согласованность данных.
     * Отсутствие корзины вызывает {@code NOT_FOUND}, недоступный снимок или превышение лимита ответа — {@code
     * DEPENDENCY_UNAVAILABLE}.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
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
     * Заменяет итоговое количество выбранного товара и возвращает обновлённую корзину. До записи блокирует
     * корзину, проверяет её версию и блокирует остаток. Нехватка товара вызывает {@code INSUFFICIENT_STOCK};
     * количество на складе не меняется. Изменение позиции и версии сохраняется вместе, а при ошибке отменяется
     * целиком.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка магазина
     * @param input новое итоговое количество товара и ожидаемая версия корзины
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
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
     * Удаляет позицию и возвращает обновлённую корзину. До удаления блокирует корзину и проверяет ожидаемую
     * версию. Отсутствующая позиция вызывает {@code NOT_FOUND}; при любой ошибке состав и версия остаются
     * прежними.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param stock UUID позиции остатка магазина
     * @param expected ожидаемая версия корзины
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    @Transactional
    public Cart delete(String store,UUID cart,UUID stock,long expected) {
        editable(header(store,cart,true),expected);
        if(repository.deleteItem(store, cart, stock)==0)
            throw new ShopException(404,"NOT_FOUND","Cart item not found");
        advance(store,cart); return mutationView(store,cart);
    }
    /**
     * Читает состояние и версию корзины указанного магазина. По запросу удерживает блокировку строки до
     * завершения текущей транзакции; отсутствие корзины вызывает {@code NOT_FOUND}.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param lock нужно ли заблокировать строку до завершения текущей транзакции
     * @return состояние и версия найденной корзины
     */
    private Map<String,Object> header(String store,UUID cart,boolean lock) {
        List<Map<String,Object>> rows=repository.headers(store, cart, lock);
        if(rows.isEmpty()) throw new ShopException(404,"NOT_FOUND","Cart not found"); return rows.getFirst();
    }
    /**
     * Проверяет, что корзина открыта и её версия совпадает с ожидаемой. Закрытая корзина вызывает {@code
     * CART_ALREADY_SUBMITTED}, изменённая версия — {@code CART_VERSION_CONFLICT}, неверная или исчерпанная
     * версия — {@code VALIDATION_ERROR}.
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
     * Увеличивает версию корзины на один в текущей транзакции. Это происходит и при повторной установке того
     * же количества; отмена транзакции отменяет увеличение.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     */
    private void advance(String store,UUID cart) { repository.advance(store, cart); }
    /**
     * Читает корзину после изменения, пока транзакция ещё открыта. Если ответ нельзя представить в допустимом
     * формате, заменяет ошибку HTTP 503 на {@code VALIDATION_ERROR}, чтобы отменить изменение. Остальные
     * ошибки передаёт вызывающему методу.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    private Cart mutationView(String store,UUID cart) {
        try { return get(store,cart); }
        catch(ShopException failure) {
            if(failure.status()==503) throw new ShopException(400,"VALIDATION_ERROR","Cart amount exceeds the v1 money limit");
            throw failure;
        }
    }
}
