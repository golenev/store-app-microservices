package com.tariffs.dto;


/**
 * Имя поля и причина нарушения его ограничения. Отклонённое значение поля в модель не входит.
 *
 * @param field имя читаемого поля
 * @param message пояснение для клиента без секретов и внутренних подробностей
 */
public record ErrorDetail(String field, String message) { }
