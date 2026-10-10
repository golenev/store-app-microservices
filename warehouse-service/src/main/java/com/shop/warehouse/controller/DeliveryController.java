package com.shop.warehouse.controller;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Published;
import com.shop.warehouse.dto.View;
import com.shop.warehouse.service.DeliveryService;

import org.springframework.http.*;
import com.shop.warehouse.service.SupplierDeliveryService;
import org.springframework.web.bind.annotation.*;

/**
 * Принимает HTTP-запросы чтения поставок, ручного повтора расчёта и отправки учебной поставки в Kafka.
 */
@RestController
public class DeliveryController {
    private final DeliveryService store;
    private final DeliveryCodec codec;
    private final SupplierDeliveryService supplier;

    /**
     * Подключает чтение и изменение поставок, проверку идентификаторов и отправку поставщиком.
     *
     * @param store транзакции приёмки, расчёта и очереди событий поставок
     * @param codec проверка событий поставок и преобразование сохранённых моделей
     * @param supplier проверка и отправка события поставщика
     */
    public DeliveryController(DeliveryService store, DeliveryCodec codec, SupplierDeliveryService supplier) {
        this.store = store;
        this.codec = codec;
        this.supplier = supplier;
    }

    /**
     * Возвращает состояние поставки указанного магазина. Неизвестная поставка или обращение через другой
     * магазин вызывает {@code NOT_FOUND}.
     *
     * @param storeId идентификатор магазина
     * @param deliveryId идентификатор поставки внутри магазина
     * @return состояние поставки с датами, попытками и строками
     */
    @GetMapping("/stores/{storeId}/deliveries/{deliveryId}")
    public View get(@PathVariable String storeId, @PathVariable String deliveryId) {
        return store.view(codec.identifier(storeId), codec.identifier(deliveryId));
    }

    /**
     * Назначает ближайший фоновый расчёт поставки и возвращает HTTP 202 с её состоянием. Если расчёт уже
     * выполняется, его владелец сохраняется; сам HTTP-запрос цены не рассчитывает.
     *
     * @param storeId идентификатор магазина
     * @param deliveryId идентификатор поставки внутри магазина
     * @return HTTP 202 с состоянием поставки после назначения повтора
     */
    @PostMapping("/stores/{storeId}/deliveries/{deliveryId}/retry-pricing")
    public ResponseEntity<View> retry(@PathVariable String storeId, @PathVariable String deliveryId) {
        return ResponseEntity.accepted().body(store.retry(codec.identifier(storeId), codec.identifier(deliveryId)));
    }

    /**
     * Проверяет и отправляет исходное событие поставщика. Возвращает HTTP 202 после подтверждения Kafka;
     * сохранение поставки и её оприходование выполняются потребителем позднее.
     *
     * @param raw исходный JSON события поставщика без изменения идентификаторов и содержимого
     * @return HTTP 202 с подтверждением отправки события поставщика
     */
    @PostMapping(value="/technical/deliveries", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Published> publish(@RequestBody String raw) {
        return ResponseEntity.accepted().body(supplier.publish(raw));
    }

}
