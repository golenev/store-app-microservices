package com.warehouse.repository;

import com.warehouse.entity.Tariff;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TariffRepository extends JpaRepository<Tariff, String> {
}
