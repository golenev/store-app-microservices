package com.shop.warehouse.controller;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.dto.Published;
import com.shop.warehouse.dto.View;
import com.shop.warehouse.service.DeliveryService;

import org.springframework.http.*;
import com.shop.warehouse.service.SupplierDeliveryService;
import org.springframework.web.bind.annotation.*;

/** Предоставляет диагностику поставок магазина и HTTP-вход учебного поставщика. */
@RestController
public class DeliveryController {
    private final DeliveryService store;
    private final DeliveryCodec codec;
    private final SupplierDeliveryService supplier;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param store сервис транзакций поставок и outbox
     * @param codec строгий разбор и сериализация протокола
     * @param supplier сервис проверки и публикации учебной поставки
     */
    public DeliveryController(DeliveryService store, DeliveryCodec codec, SupplierDeliveryService supplier) {
        this.store = store;
        this.codec = codec;
        this.supplier = supplier;
    }

    /**
     * Возвращает поставку deliveryId магазина storeId; отсутствующая или принадлежащая другому магазину запись
     * вызывает NOT_FOUND.
     *
     * @param storeId идентификатор магазина
     * @param deliveryId идентификатор поставки из HTTP-маршрута
     */
    @GetMapping("/stores/{storeId}/deliveries/{deliveryId}")
    public View get(@PathVariable String storeId, @PathVariable String deliveryId) {
        return store.view(codec.identifier(storeId), codec.identifier(deliveryId));
    }

    /**
     * Возвращает 202 после ускорения фонового расчёта deliveryId магазина storeId; токен активной попытки
     * сохраняется.
     *
     * @param storeId идентификатор магазина
     * @param deliveryId идентификатор поставки из HTTP-маршрута
     * @return HTTP-ответ с описанным статусом и телом
     */
    @PostMapping("/stores/{storeId}/deliveries/{deliveryId}/retry-pricing")
    public ResponseEntity<View> retry(@PathVariable String storeId, @PathVariable String deliveryId) {
        return ResponseEntity.accepted().body(store.retry(codec.identifier(storeId), codec.identifier(deliveryId)));
    }

    /**
     * Передаёт исходный raw сервису поставщика; возвращает 202 после подтверждения Kafka без обещания приёмки
     * или POSTED.
     *
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @return HTTP-ответ с описанным статусом и телом
     */
    @PostMapping(value="/technical/deliveries", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Published> publish(@RequestBody String raw) {
        return ResponseEntity.accepted().body(supplier.publish(raw));
    }

}
