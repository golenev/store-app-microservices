package com.shop.store.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * Состояние корзины: магазин, версия, позиции и итоговая сумма. После оформления содержит сохранённые на
 * тот момент цены и ссылку на принятую заявку.
 *
 * @param storeId идентификатор магазина
 * @param cartId идентификатор корзины
 * @param version версия корзины
 * @param state состояние корзины: {@code OPEN} или {@code SUBMITTED}
 * @param items позиции корзины с количеством, ценой и суммой каждой позиции
 * @param totalAmount сумма всех позиций корзины строкой с двумя знаками после точки
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 * @param submissionId идентификатор принятой заявки
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Cart(String storeId, UUID cartId, long version, String state, List<CartLine> items, String totalAmount, String currency, UUID submissionId) { }
