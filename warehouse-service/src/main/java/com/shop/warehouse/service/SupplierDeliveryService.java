package com.shop.warehouse.service;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Published;
import com.shop.warehouse.exception.DeliveryException;
import com.shop.warehouse.messaging.DeliveryPublisher;
import com.shop.warehouse.model.Accepted;
import org.springframework.stereotype.Service;
import java.time.Clock;

/**
 * Проверяет учебную поставку и отправляет её событие в Kafka. Сам не сохраняет поставку в БД WAREHOUSE.
 */
@Service
public class SupplierDeliveryService {
    private final DeliveryCodec codec;
    private final DeliveryPublisher publisher;
    private final Clock clock;

    /**
     * Подключает проверку события, отправку в Kafka и часы для времени ответа поставщику.
     *
     * @param codec проверка событий поставок и преобразование сохранённых моделей
     * @param publisher отправка исходного события поставщика в Kafka
     * @param clock часы для дат операций и сроков фоновых попыток
     */
    public SupplierDeliveryService(DeliveryCodec codec, DeliveryPublisher publisher, Clock clock) {
        this.codec = codec;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Проверяет событие поставщика и отправляет исходный JSON без изменений. Неверные данные отклоняет с HTTP
     * 400. После подтверждения Kafka возвращает {@code PUBLISHED}; приёмка и расчёт поставки выполняются
     * позднее. Повтор с прежними идентификаторами позволяет потребителю распознать уже обработанную поставку.
     *
     * @param raw исходный JSON события поставщика без изменения идентификаторов и содержимого
     * @return подтверждение отправки поставщиком с идентификаторами и временем
     */
    public Published publish(String raw) {
        Accepted input = codec.decode(raw);
        if (input.rejection() != null)
            throw new DeliveryException(400, input.rejection().code(), input.rejection().message());
        publisher.publish(input.storeId(), raw);
        return new Published(input.eventId(), input.storeId(), input.deliveryId(), "PUBLISHED", clock.instant());
    }
}
