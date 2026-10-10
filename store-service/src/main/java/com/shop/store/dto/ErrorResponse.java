package com.shop.store.dto;

import java.time.Instant;

/**
 * Ответ об ошибке HTTP: время в UTC, статус, код, пояснение и путь запроса. Пояснение предназначено
 * клиенту и не должно содержать внутренних данных приложения.
 *
 * @param timestamp время формирования ошибки в UTC
 * @param status HTTP-статус ответа
 * @param code код, по которому клиент различает причину ошибки
 * @param message пояснение для клиента без секретов и внутренних подробностей
 * @param path путь HTTP-запроса
 */
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path) { }
