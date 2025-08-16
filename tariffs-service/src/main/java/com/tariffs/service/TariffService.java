package com.tariffs.service;

import com.tariffs.entity.Tariff;
import com.tariffs.repository.TariffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
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
        simulateBusinessLogicDelay();
        return repository.findAll();
    }

    private void simulateBusinessLogicDelay() {
        log.info("Пауза 5 секунд: имитация долгой бизнес-логики перед чтением тарифов");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    @CacheEvict(value = "tariffs", allEntries = true) // очищает кэш по запросу
    public void resetCache() {
        log.info("Tariff cache reset on demand");
    }

    @Scheduled(cron = "0 0 6 * * *") // запускает метод ежедневно в 6:00
    @CacheEvict(value = "tariffs", allEntries = true) // очищает кэш, чтобы следующий запрос перечитал БД
    public void clearCache() {
        log.info("Evicting tariffs cache at 6 AM");
    }
}
