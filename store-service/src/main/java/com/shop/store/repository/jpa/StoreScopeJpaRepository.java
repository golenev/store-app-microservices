package com.shop.store.repository.jpa;

import com.shop.store.entity.StoreScopeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

/** Читает магазины и сериализует приёмку поставок блокировкой строки магазина. */
public interface StoreScopeJpaRepository extends JpaRepository<StoreScopeEntity, String> {
    /**
     * Блокирует магазин до окончания транзакции приёмки.
     * @param store идентификатор магазина
     * @return найденный магазин или отсутствие; блокировку получает только найденная строка
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StoreScopeEntity s where s.storeId = :store")
    Optional<StoreScopeEntity> lockStore(String store);
}
