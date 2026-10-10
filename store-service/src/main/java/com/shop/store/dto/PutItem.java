package com.shop.store.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Параметры установки количества товара в корзине. Количество заменяет прежнее; ожидаемая версия нужна для
 * обнаружения одновременного изменения.
 *
 * @param quantity количество единиц товара
 * @param expectedCartVersion версия корзины, которую клиент ожидает перед изменением
 */
public record PutItem(@Min(1) int quantity,
        @Min(0) @Max(9007199254740991L) long expectedCartVersion) { }
