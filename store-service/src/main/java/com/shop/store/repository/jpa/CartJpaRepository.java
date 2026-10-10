package com.shop.store.repository.jpa;

import com.shop.store.entity.CartEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

/** Читает корзины внутри магазина; изменения сериализует пессимистической блокировкой. */
public interface CartJpaRepository extends JpaRepository<CartEntity, UUID> {
    /**
     * Читает корзину без блокировки и без загрузки позиций.
     * @param store магазин
     * @param cart корзина
     * @return корзина или отсутствие
     */
    Optional<CartEntity> findByStoreIdAndCartId(String store, UUID cart);

    /**
     * Блокирует корзину до окончания изменения или оформления.
     * @param store магазин
     * @param cart корзина
     * @return управляемая корзина или отсутствие
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartEntity c where c.storeId = :store and c.cartId = :cart")
    Optional<CartEntity> lockCart(String store, UUID cart);
}
