package com.shop.store.repository;

import com.shop.store.dto.Catalog;
import com.shop.store.dto.Stock;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.model.ExistingStock;

import jakarta.persistence.EntityManager;
import com.shop.store.entity.*;
import com.shop.store.repository.jpa.*;
import com.shop.store.model.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import java.util.*;

/**
 * Читает каталог магазина и блокирует остатки в PostgreSQL. Блокировки действуют до завершения транзакции
 * вызывающего сервиса.
 */
@Repository
public class InventoryRepository {
    private final InventoryJpaRepository stocks;
    private final StoreScopeJpaRepository stores;
    /**
     * Подключает Spring Data JPA; блокировки участвуют в транзакции вызывающего сервиса.
     * @param stocks чтение и блокировка остатков
     * @param stores чтение магазинов
     */
    public InventoryRepository(InventoryJpaRepository stocks, StoreScopeJpaRepository stores) {
        this.stocks = stocks;
        this.stores = stores;
    }
    /**
     * Проверяет, что магазин существует. Для неизвестного магазина выдаёт {@code NOT_FOUND}; новые магазины не
     * создаёт.
     *
     * @param store идентификатор магазина
     */
    public void requireStore(String store) {
        if (!stores.existsById(store)) throw new ShopException(404, "NOT_FOUND", "Store not found");
    }
    /**
     * Возвращает каталог магазина с ценами и доступным количеством, включая нулевые остатки. Неизвестный
     * магазин вызывает {@code NOT_FOUND}, более 1000 позиций — {@code DEPENDENCY_UNAVAILABLE}, поскольку ответ
     * превышает лимит API.
     *
     * @param store идентификатор магазина
     * @return каталог указанного магазина с текущими ценами и остатками
     */
    public Catalog catalog(String store) {
        requireStore(store);
        List<Stock> rows = stocks.findByStoreIdOrderByProductId(store, PageRequest.of(0, 1001)).stream()
                .map(this::stock).toList();
        if (rows.size() > 1000) throw new ShopException(503, "DEPENDENCY_UNAVAILABLE", "Inventory exceeds the v1 catalog limit");
        return new Catalog(store, rows);
    }
    /**
     * Блокирует уже существующие остатки товаров входящей поставки в порядке UUID. Возвращает их по
     * идентификатору продукта для выбора новой цены и проверки количества; блокировки держит до конца
     * транзакции сервиса.
     *
     * @param store идентификатор магазина
     * @param lines проверенные строки оприходованной поставки
     * @return существующие остатки по идентификатору продукта; новые продукты в карту не входят
     */
    public Map<String,ExistingStock> lockIncoming(String store,List<PostedLine> lines) {
        Map<String, ExistingStock> result = new HashMap<>();
        for (InventoryEntity entity : stocks.lockIncoming(store, lines.stream().map(PostedLine::productId).toList())) {
            result.put(entity.getProductId(), new ExistingStock(stock(entity), entity.getLastDeliverySequence()));
        }
        return result;
    }
    /**
     * Находит и блокирует позицию остатка указанного магазина. Отсутствие вызывает {@code NOT_FOUND};
     * блокировка действует до конца транзакции и сама по себе не резервирует товар.
     *
     * @param store идентификатор магазина
     * @param stock UUID позиции остатка магазина
     * @return найденная позиция остатка магазина
     */
    public Stock lockStock(String store,UUID stock) {
        return stocks.lockStock(store, stock).map(this::stock)
                .orElseThrow(() -> new ShopException(404, "NOT_FOUND", "Stock item not found"));
    }
    /**
     * Отделяет публичный DTO от управляемой сущности; цену передаёт точной строкой.
     * @param entity найденный остаток
     * @return позиция каталога без зависимости от жизненного цикла JPA
     */
    private Stock stock(InventoryEntity entity) {
        return new Stock(entity.getStockItemId(), entity.getProductId(), entity.getProductType(),
                entity.getShortName(), entity.getDescription(), entity.getUnitPrice().toPlainString(),
                entity.getCurrency(), entity.getAvailableQuantity());
    }
}
