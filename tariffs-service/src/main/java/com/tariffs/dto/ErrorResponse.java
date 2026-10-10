package com.tariffs.dto;

import java.time.Instant;
import java.util.List;

/**
 * Ответ об ошибке HTTP: время в UTC, статус, код, пояснение и путь запроса. Пояснение предназначено
 * клиенту и не должно содержать внутренних данных приложения.
 *
 * @param timestamp время формирования ошибки в UTC
 * @param status HTTP-статус ответа
 * @param code код, по которому клиент различает причину ошибки
 * @param message пояснение для клиента без секретов и внутренних подробностей
 * @param path путь HTTP-запроса
 * @param details имена полей и причины нарушения без отклонённых значений
 */
public record ErrorResponse(Instant timestamp, int status, String code, String message,
                                String path, List<ErrorDetail> details) { }
