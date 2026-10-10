package com.shop.store.dto;

/**
 * Параметры установки количества товара в корзине. Количество заменяет прежнее; ожидаемая версия нужна для
 * обнаружения одновременного изменения.
 *
 * @param quantity количество единиц товара
 * @param expectedCartVersion версия корзины, которую клиент ожидает перед изменением
 */
public record PutItem(int quantity, long expectedCartVersion) { }
