package com.shop.warehouse.dto;

/**
 * Код и пояснение причины, по которой поставка отклонена или расчёт её цен не завершён.
 *
 * @param code код, по которому клиент различает причину ошибки
 * @param message пояснение для клиента без секретов и внутренних подробностей
 */
public record Failure(String code, String message) { }
