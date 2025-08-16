package com.tariffs.service;

import com.tariffs.entity.Tariff;
import com.tariffs.repository.TariffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service // помечает класс как сервис Spring и регистрирует его в контексте
@RequiredArgsConstructor // генерирует конструктор с требуемыми зависимостями
@Slf4j // подключает логгер Lombok с именем "log"
public class TariffService {

    private final TariffRepository repository;

    @Cacheable("tariffs") // кэширует список тарифов в кеше "tariffs"
    public List<Tariff> findAll() {
        return repository.findAll();
    }

    @CacheEvict(value = "tariffs", allEntries = true) // очищает весь кэш "tariffs" при создании
    public Tariff create(Tariff tariff) {
        return repository.save(tariff);
    }

    @CacheEvict(value = "tariffs", allEntries = true) // удаляет кэш перед обновлением
    public Optional<Tariff> update(String productType, Tariff tariff) {
        return repository.findById(productType)
                .map(existing -> {
                    existing.setMarkupCoefficient(tariff.getMarkupCoefficient());
                    return repository.save(existing);
                });
    }

    @CacheEvict(value = "tariffs", allEntries = true) // удаляет кэш перед удалением
    public boolean delete(String productType) {
        if (!repository.existsById(productType)) {
            return false;
        }
        repository.deleteById(productType);
        return true;
    }

    @Scheduled(cron = "0 0 6 * * *") // запускает метод ежедневно в 6:00
    @CachePut("tariffs") // обновляет значение кэша "tariffs" данными из БД
    public List<Tariff> refreshCache() {
        log.info("Refreshing tariffs cache from DB");
        return repository.findAll();
    }
}
