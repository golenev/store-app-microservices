package com.shop.store.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Ожидаемая версия корзины для оформления. Состав, цены и сумму сервер читает из своих данных.
 *
 * @param expectedCartVersion версия корзины, которую клиент ожидает перед изменением
 */
public record SubmitInput(@Min(0) @Max(9007199254740991L) long expectedCartVersion) { }
