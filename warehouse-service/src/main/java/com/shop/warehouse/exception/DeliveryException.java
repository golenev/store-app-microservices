package com.shop.warehouse.exception;

/** Передаёт намеренные статус, код и безопасное пояснение ошибки без инфраструктурных секретов. */
public class DeliveryException extends RuntimeException {
    private final int status;
    private final String code;
    /**
     * Сохраняет status, code и безопасный message; переданные подробности валидации копирует в неизменяемый
     * список.
     *
     * @param status намеренный HTTP-статус
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     */
    public DeliveryException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    /**
     * Возвращает намеренный HTTP-статус ошибки; Kafka-потребитель может сохранить его как диагностику.
     *
     * @return намеренный HTTP-статус ошибки
     */
    public int status() { return status; }
    /**
     * Возвращает стабильный машинный код ошибки публичного контракта.
     */
    public String code() { return code; }
}
