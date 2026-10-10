package com.shop.warehouse.service;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Published;
import com.shop.warehouse.exception.DeliveryException;
import com.shop.warehouse.messaging.DeliveryPublisher;
import com.shop.warehouse.model.Accepted;
import org.springframework.stereotype.Service;
import java.time.Clock;

/** Проверяет учебную поставку и координирует её публикацию без SQL-транзакции. */
@Service
public class SupplierDeliveryService {
    private final DeliveryCodec codec;
    private final DeliveryPublisher publisher;
    private final Clock clock;

    /**
     * Получает проверку контракта, publisher и UTC-часы; конструктор не выполняет внешних запросов.
     *
     * @param codec строгий разбор и сериализация протокола
     * @param publisher адаптер публикации исходного события
     * @param clock общие UTC-часы приложения
     */
    public SupplierDeliveryService(DeliveryCodec codec, DeliveryPublisher publisher, Clock clock) {
        this.codec = codec;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Проверяет исходный JSON и публикует его без изменений. Невалидная поставка отклоняется с 400. Возвращает
     * PUBLISHED только после подтверждения брокера; приёмка и POSTED выполняются позднее. Повтор raw сохраняет
     * идентификаторы для защиты от повторного прихода на стороне потребителя.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    public Published publish(String raw) {
        Accepted input = codec.decode(raw);
        if (input.rejection() != null)
            throw new DeliveryException(400, input.rejection().code(), input.rejection().message());
        publisher.publish(input.storeId(), raw);
        return new Published(input.eventId(), input.storeId(), input.deliveryId(), "PUBLISHED", clock.instant());
    }
}
