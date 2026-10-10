package com.shop.store.model;

/**
 * Состояние корзины без передачи JPA-сущности в сервис.
 * @param state OPEN или SUBMITTED
 * @param version версия публичного API
 */
public record CartHeader(String state, long version) { }
