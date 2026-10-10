package com.shop.store.messaging.dto;

import com.shop.store.dto.CartLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Содержимое принятой заявки: корзина, её позиции и сумма на момент оформления. Сохраняется вместе со
 * списанием товара и далее не меняется.
 *
 * @param submissionId идентификатор принятой заявки
 * @param cartId идентификатор корзины
 * @param acceptedAt время принятия заявки на оформление
 * @param items позиции корзины с количеством, ценой и суммой каждой позиции
 * @param totalAmount сумма всех позиций корзины строкой с двумя знаками после точки
 * @param currency код валюты; в текущем контракте разрешён {@code RUB}
 */
public record OrderPayload(UUID submissionId, UUID cartId, Instant acceptedAt, List<CartLine> items, String totalAmount, String currency) { }
