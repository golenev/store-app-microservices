package com.shop.store.repository.jpa;

import com.shop.store.entity.InventoryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Читает остатки через производные запросы и JPQL, блокирует их в порядке UUID PostgreSQL. */
public interface InventoryJpaRepository extends JpaRepository<InventoryEntity, UUID> {
    /**
     * Читает ограниченный каталог в порядке продукта.
     * @param store магазин
     * @param limit ограничение размера выборки
     * @return остатки, включая нулевые
     */
    List<InventoryEntity> findByStoreIdOrderByProductId(String store, Pageable limit);

    /**
     * Считает товары магазина без загрузки сущностей.
     * @param store магазин
     * @return число позиций
     */
    long countByStoreId(String store);

    /**
     * Блокирует существующие товары поставки в порядке UUID до конца текущей транзакции.
     * @param store магазин
     * @param products идентификаторы продуктов непустой поставки
     * @return найденные управляемые остатки
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.storeId = :store and i.productId in :products order by i.stockItemId")
    List<InventoryEntity> lockIncoming(String store, List<String> products);

    /**
     * Блокирует одну позицию указанного магазина до завершения транзакции.
     * @param store магазин
     * @param stock UUID остатка
     * @return управляемый остаток или отсутствие
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.storeId = :store and i.stockItemId = :stock")
    Optional<InventoryEntity> lockStock(String store, UUID stock);

    /**
     * Блокирует только inventory из JOIN корзины в порядке UUID. Native SQL сохраняет FOR UPDATE OF i;
     * блокировка корзины уже получена сервисом, все блокировки живут до конца оформления.
     * @param store магазин
     * @param cart корзина
     * @return управляемые остатки всех позиций корзины
     */
    @Query(value = """
            SELECT i.* FROM cart_items c JOIN inventory i
            ON i.store_id=c.store_id AND i.stock_item_id=c.stock_item_id
            WHERE c.store_id=:store AND c.cart_id=:cart ORDER BY i.stock_item_id FOR UPDATE OF i
            """, nativeQuery = true)
    List<InventoryEntity> lockCheckout(String store, UUID cart);
}
