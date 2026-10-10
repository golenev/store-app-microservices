package com.tariffs.exception;

import com.tariffs.dto.ErrorDetail;

import java.util.List;

/**
 * Передаёт HTTP-статус, код и пояснение ошибки тарифов. При нарушении полей может также содержать список
 * их имён и причин отказа.
 */
public class TariffApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<ErrorDetail> details;

    /**
     * Создаёт исключение с указанными статусом, кодом и пояснением без списка ошибок полей.
     *
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    public TariffApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    /**
     * Создаёт исключение и копирует ошибки полей в неизменяемый список. Пояснения должен подготовить
     * вызывающий код без паролей, SQL и отклонённых значений.
     *
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @param details имена полей и причины нарушения без отклонённых значений
     */
    public TariffApiException(int status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    /**
     * Возвращает HTTP-статус, заданный при создании исключения.
     *
     * @return HTTP-статус исключения
     */
    public int status() { return status; }
    /**
     * Возвращает код, по которому клиент различает причину ошибки.
     *
     * @return код причины ошибки, заданный при создании исключения
     */
    public String code() { return code; }
    /**
     * Возвращает неизменяемый список имён полей и причин отказа. Пустой список означает, что отдельные ошибки
     * полей не переданы.
     *
     * @return ошибки полей; пустой список, если отдельные ошибки не переданы
     */
    public List<ErrorDetail> details() { return details; }
}
