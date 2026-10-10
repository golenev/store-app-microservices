package com.shop.store.model;

/**
 * Содержимое ранее обработанного события для проверки повтора.
 * @param storeId магазин
 * @param deliveryId поставка
 * @param fingerprint контрольная сумма содержимого
 */
public record ProcessedGoods(String storeId, String deliveryId, String fingerprint) { }
