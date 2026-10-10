package com.shop.store.repository.jpa;

import com.shop.store.entity.CartItemEntity;
import com.shop.store.entity.CartItemId;
import com.shop.store.model.CheckoutLine;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

/** Соединяет позиции корзины с текущими остатками одной JPQL-проекцией без N+1. */
public interface CartItemJpaRepository extends JpaRepository<CartItemEntity, CartItemId> {
    /**
     * Читает цену и количество одной выборкой; данные передаются в типизированную проекцию.
     * @param store магазин
     * @param cart корзина
     * @param limit ограничение числа позиций
     * @return позиции в порядке UUID остатка
     */
    @Query("""
            select new com.shop.store.model.CheckoutLine(i.stockItemId, i.productId, i.shortName,
                i.unitPrice, i.availableQuantity, c.quantity)
            from CartItemEntity c join c.stock i
            where i.storeId = c.storeId and c.storeId = :store and c.cartId = :cart order by i.stockItemId
            """)
    List<CheckoutLine> lines(String store, UUID cart, Pageable limit);
}
