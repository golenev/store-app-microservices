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
                    new Tariff(null, "food_100", BigDecimal.valueOf(1)),
                    new Tariff(null, "food_300", BigDecimal.valueOf(3)),
                    new Tariff(null, "food_500", BigDecimal.valueOf(5)),
                    new Tariff(null, "food_1000", BigDecimal.valueOf(10)),
                    new Tariff(null, "not_food_100", BigDecimal.valueOf(5)),
                    new Tariff(null, "not_food_500", BigDecimal.valueOf(10)),
                    new Tariff(null, "not_food_1000", BigDecimal.valueOf(20))
            );
            repository.saveAll(tariffs);
        }
    }
}

