package com.warehouse.config;

import com.warehouse.entity.Tariff;
import com.warehouse.repository.TariffRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class TariffDataInitializer implements CommandLineRunner {

    private final TariffRepository repository;

    public TariffDataInitializer(TariffRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() == 0) {
            List<Tariff> tariffs = List.of(
                    new Tariff(null, "FOOD_LT_100", BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.valueOf(5), BigDecimal.valueOf(1.05)),
                    new Tariff(null, "FOOD_300_500", BigDecimal.valueOf(300), BigDecimal.valueOf(500), BigDecimal.valueOf(7), BigDecimal.valueOf(1.07)),
                    new Tariff(null, "FOOD_500_1000", BigDecimal.valueOf(500), BigDecimal.valueOf(1000), BigDecimal.valueOf(8), BigDecimal.valueOf(1.08)),
                    new Tariff(null, "FOOD_GT_1000", BigDecimal.valueOf(1000), null, BigDecimal.valueOf(10), BigDecimal.valueOf(1.10)),
                    new Tariff(null, "NON_FOOD_GT_100", BigDecimal.valueOf(100), BigDecimal.valueOf(500), BigDecimal.valueOf(7), BigDecimal.valueOf(1.07)),
                    new Tariff(null, "NON_FOOD_500_1000", BigDecimal.valueOf(500), BigDecimal.valueOf(1000), BigDecimal.valueOf(10), BigDecimal.valueOf(1.10)),
                    new Tariff(null, "NON_FOOD_GT_1000", BigDecimal.valueOf(1000), null, BigDecimal.valueOf(15), BigDecimal.valueOf(1.15))
            );
            repository.saveAll(tariffs);
        }
    }
}

