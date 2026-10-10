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

/**
 * По расписанию выбирает ожидающие поставки и запрашивает тарифы для их строк. HTTP-запросы выполняет
 * между транзакциями назначения владельца и сохранения результата.
 */
@Service
@ConditionalOnProperty(name="warehouse.workers.enabled", havingValue="true", matchIfMissing=true)
public class DeliveryPricingService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryPricingService.class);
    private final DeliveryService store;
    private final TariffClient tariffs;

    /**
     * Подключает операции с поставками и HTTP-клиент тарифов.
     *
     * @param store транзакции приёмки, расчёта и очереди событий поставок
     * @param tariffs HTTP-запрос наценки и вычисление продажной цены
     */
    public DeliveryPricingService(DeliveryService store, TariffClient tariffs) {
        this.store = store;
        this.tariffs = tariffs;
    }

    /**
     * Запускает обработку одной готовой к расчёту поставки по расписанию. Неожиданную ошибку пишет в журнал;
     * после истечения срока владения сохранённую поставку можно обработать снова.
     */
    @Scheduled(fixedDelayString="${warehouse.pricing-poll-ms:500}")
    public void pricingTick() {
        try { priceOne(); }
        catch (Exception failure) { log.error("Pricing worker failed; persisted lease will recover", failure); }
    }

    /**
     * Берёт одну поставку и перед каждым запросом тарифа продлевает срок владения. Если владелец сменился,
     * прекращает попытку. Все рассчитанные строки передаёт на сохранение одной транзакцией; ошибку тарифа
     * сохраняет как причину ожидания следующей попытки.
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
