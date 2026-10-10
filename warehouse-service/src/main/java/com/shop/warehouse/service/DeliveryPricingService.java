package com.shop.warehouse.service;

import com.shop.warehouse.dto.Line;
import com.shop.warehouse.dto.Failure;
import com.shop.warehouse.exception.DeliveryException;
import com.shop.warehouse.model.PricingWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Optional;
import com.shop.warehouse.client.TariffClient;
import java.util.ArrayList;
import java.util.List;

/** Координирует расчёт поставки; HTTP выполняется между короткими транзакциями захвата и фиксации. */
@Service
@ConditionalOnProperty(name="warehouse.workers.enabled", havingValue="true", matchIfMissing=true)
public class DeliveryPricingService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryPricingService.class);
    private final DeliveryService store;
    private final TariffClient tariffs;

    /**
     * Получает транзакционный сервис и внешний адаптер; конструктор не выполняет запросов.
     *
     * @param store сервис транзакций поставок и outbox
     * @param tariffs HTTP-клиент выбора тарифа
     */
    public DeliveryPricingService(DeliveryService store, TariffClient tariffs) {
        this.store = store;
        this.tariffs = tariffs;
    }

    /**
     * Запускает наступившие попытки расчёта. Сохранённый захват позволяет восстановиться без ручного повтора.
     */
    @Scheduled(fixedDelayString="${warehouse.pricing-poll-ms:500}")
    public void pricingTick() {
        try { priceOne(); }
        catch (Exception failure) { log.error("Pricing worker failed; persisted lease will recover", failure); }
    }

    /**
     * Захватывает одну поставку и продлевает владение перед каждой строкой. Результаты фиксируются одной
     * транзакцией; потерявшая владение попытка прекращает обработку.
     */
    public void priceOne() {
        Optional<PricingWork> pending = store.claimPricing();
        if (pending.isEmpty()) return;
        PricingWork work = pending.get();
        try {
            List<Line> results = new ArrayList<>();
            for (Line line : work.items()) {
                if (!store.renew(work)) return;
                results.add(tariffs.price(line, work.cityId()));
            }
            store.post(work, results);
        } catch (DeliveryException failure) {
            store.failedPricing(work, new Failure(failure.code(), failure.getMessage()));
        }
    }

}
