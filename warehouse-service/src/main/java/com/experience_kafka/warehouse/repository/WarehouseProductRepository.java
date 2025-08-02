package com.experience_kafka.warehouse.repository;

import com.experience_kafka.warehouse.model.WarehouseProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseProductRepository extends JpaRepository<WarehouseProduct, Long> {
}
