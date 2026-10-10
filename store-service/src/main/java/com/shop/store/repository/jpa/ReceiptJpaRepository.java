package com.shop.store.repository.jpa;

import com.shop.store.entity.ReceiptEntity;
import com.shop.store.entity.ReceiptId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Читает принятые поставки по составному ключу магазина и поставки. */
public interface ReceiptJpaRepository extends JpaRepository<ReceiptEntity, ReceiptId> {
    /**
     * Проверяет занятый порядок первой приёмки без загрузки поставок.
     * @param store магазин
     * @param sequence порядок приёмки
     * @return число поставок с этим порядком
     */
    long countByStoreIdAndDeliverySequence(String store, long sequence);
}
