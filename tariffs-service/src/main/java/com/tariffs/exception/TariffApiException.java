package com.tariffs.exception;

import com.tariffs.dto.ErrorDetail;

import java.util.List;

/** Передаёт намеренные статус, код и безопасное пояснение ошибки без инфраструктурных секретов. */
public class TariffApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<ErrorDetail> details;

    /**
     * Сохраняет status, code и безопасный message; переданные подробности валидации копирует в неизменяемый
     * список.
     *
     * @param status намеренный HTTP-статус
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     */
    public TariffApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    /**
     * Сохраняет status, code и безопасный message; переданные подробности валидации копирует в неизменяемый
     * список.
     *
     * @param status намеренный HTTP-статус
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     * @param details подробности нарушений полей без отклонённых значений
     */
    public TariffApiException(int status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
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
    /**
     * Возвращает неизменяемые подробности нарушений полей без отклонённых значений.
     *
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<ErrorDetail> details() { return details; }
}
