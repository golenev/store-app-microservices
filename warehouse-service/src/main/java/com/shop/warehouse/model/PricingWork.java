package com.shop.warehouse.model;

import com.shop.warehouse.dto.Line;

import java.util.List;
import java.util.UUID;

/**
 * Поставка, выбранная для расчёта: магазин, город, строки, номер попытки и идентификатор владельца. По
 * нему проверяется право продолжить работу и сохранить цены.
 *
 * @param storeId идентификатор магазина
 * @param deliveryId идентификатор поставки внутри магазина
 * @param cityId идентификатор города, для которого выбирается тариф
 * @param token UUID текущего владельца фоновой попытки
 * @param attemptCount число начатых попыток обработки
 * @param items строки поставки с количеством, ценами и данными тарифа
 */
public record PricingWork(String storeId, String deliveryId, String cityId, UUID token,
                              long attemptCount, List<Line> items) { }
