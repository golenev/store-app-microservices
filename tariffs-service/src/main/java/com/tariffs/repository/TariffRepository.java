package com.tariffs.repository;

import com.tariffs.entity.Tariff;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TariffRepository extends JpaRepository<Tariff, String> {
}
