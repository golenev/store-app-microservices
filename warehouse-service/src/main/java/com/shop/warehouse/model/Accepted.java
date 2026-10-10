package com.shop.warehouse.model;

import com.shop.warehouse.dto.Failure;
import com.shop.warehouse.messaging.dto.InputLine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * Результат чтения входящего события: идентификаторы, исходное содержимое, контрольная сумма и проверенные
 * строки. При неверных строках содержит причину отклонения и пустой список строк.
 *
 * @param eventId идентификатор события для распознавания повторного сообщения
 * @param storeId идентификатор магазина
 * @param deliveryId идентификатор поставки внутри магазина
 * @param payload исходное содержимое поставки с идентификатором и строками товаров
 * @param items проверенные строки поставки до расчёта тарифа
 * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
 * @param rejection код и пояснение неудачного расчёта или отклонения поставки
 */
public record Accepted(UUID eventId, String storeId, String deliveryId, JsonNode payload,
                           List<InputLine> items, String fingerprint, Failure rejection) { }
