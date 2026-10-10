package com.shop.store.exception;

/**
 * Передаёт код ошибки, HTTP-статус и пояснение, которое можно вернуть клиенту или сохранить для
 * диагностики сообщения.
 */
public class ShopException extends RuntimeException {
    private final int status;
    private final String code;
    /**
     * Создаёт исключение с указанными статусом, кодом и пояснением. Пояснение должен подготовить вызывающий
     * код без паролей, SQL и других внутренних подробностей.
     *
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    public ShopException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    /**
     * Возвращает HTTP-статус, заданный при создании исключения.
     *
     * @return HTTP-статус исключения
     */
    public int status() { return status; }
    /**
     * Возвращает код, по которому клиент или диагностика различает причину ошибки.
     *
     * @return код причины ошибки, заданный при создании исключения
     */
    public String code() { return code; }
}
