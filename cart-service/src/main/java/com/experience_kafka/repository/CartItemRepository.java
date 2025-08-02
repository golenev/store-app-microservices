package com.experience_kafka.repository;

import com.experience_kafka.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    CartItem findByBarcodeId(Long barcodeId);
}
