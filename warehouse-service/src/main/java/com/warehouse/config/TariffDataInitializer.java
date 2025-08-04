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
                    new Tariff("food_100", BigDecimal.valueOf(1)),
                    new Tariff("food_300", BigDecimal.valueOf(3)),
                    new Tariff("food_500", BigDecimal.valueOf(5)),
                    new Tariff("food_1000", BigDecimal.valueOf(10)),
                    new Tariff("not_food_100", BigDecimal.valueOf(5)),
                    new Tariff("not_food_500", BigDecimal.valueOf(10)),
                    new Tariff("not_food_1000", BigDecimal.valueOf(20))
            );
            repository.saveAll(tariffs);
        }
    }
}

