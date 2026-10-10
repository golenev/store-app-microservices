package com.shop.store.dto;

/**
 * Ожидаемая версия корзины для оформления. Состав, цены и сумму сервер читает из своих данных.
 *
 * @param expectedCartVersion версия корзины, которую клиент ожидает перед изменением
 */
public record SubmitInput(long expectedCartVersion) { }
