package com.shop.store.model;

import com.shop.store.messaging.dto.GoodsEvent;

/** Проверенное событие и отпечаток бизнес-содержимого без транспортного eventId. */
public record Decoded(GoodsEvent event, String fingerprint) { }
