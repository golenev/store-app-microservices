package com.shop.store.exception;

import com.shop.store.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.time.Clock;

/**
 * Преобразует исключения сервиса в HTTP-ответы с кодом, пояснением, временем и путём запроса. Внутренние
 * подробности сбоев не возвращает клиенту.
 */
@RestControllerAdvice
public class ShopErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ShopErrorHandler.class);
    private final Clock clock;
    /**
     * Подключает часы для времени HTTP-ответов об ошибках.
     *
     * @param clock часы для дат операций и сроков фоновых попыток
     */
    public ShopErrorHandler(Clock clock) { this.clock=clock; }
    /**
     * Возвращает статус, код и пояснение, заданные бизнес-исключением. Путь берёт из HTTP-запроса.
     *
     * @param failure ошибка магазина с заданными статусом, кодом и пояснением
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(ShopException.class)
    public ResponseEntity<ErrorResponse> domain(ShopException failure,HttpServletRequest request) {
        return response(failure.status(),failure.code(),failure.getMessage(),request);
    }
    /**
     * Возвращает HTTP 503 при недоступности БД или сбое завершения транзакции. SQL и внутренние подробности
     * ошибки в ответ не включает.
     *
     * @param failure исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<ErrorResponse> database(Exception failure,HttpServletRequest request) {
        log.warn("STORE database operation failed",failure); return response(503,"DEPENDENCY_UNAVAILABLE","Store storage unavailable",request);
    }
    /**
     * Возвращает HTTP 400, если тело запроса нельзя прочитать или отсутствуют обязательные параметры.
     * Подробности JSON-парсера клиенту не передаёт.
     *
     * @param failure исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception failure,HttpServletRequest request) {
        return response(400,"VALIDATION_ERROR","Invalid or missing request data",request);
    }
    /**
     * Возвращает HTTP 404 для пути, которому не соответствует доступный ресурс.
     *
     * @param failure исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missing(Exception failure,HttpServletRequest request) {
        return response(404,"NOT_FOUND","Resource not found",request);
    }
    /**
     * Возвращает HTTP 405, если выбранный путь не поддерживает метод запроса.
     *
     * @param failure исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(Exception failure,HttpServletRequest request) {
        return response(405,"VALIDATION_ERROR","HTTP method not supported",request);
    }
    /**
     * Возвращает HTTP 415, если формат тела, указанный в заголовке {@code Content-Type}, не поддерживается.
     *
     * @param failure исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> media(Exception failure,HttpServletRequest request) {
        return response(415,"VALIDATION_ERROR","Content type not supported",request);
    }
    /**
     * Записывает неожиданную ошибку в журнал и возвращает HTTP 500. Стек вызовов и внутренние данные
     * приложения в ответ не входят.
     *
     * @param failure исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception failure,HttpServletRequest request) {
        log.error("Unexpected STORE API failure",failure); return response(500,"INTERNAL_ERROR","Internal store error",request);
    }
    /**
     * Собирает ответ об ошибке из заданных статуса, кода и пояснения; добавляет текущее время UTC и путь
     * запроса. Пояснение должно быть заранее подготовлено для клиента.
     *
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    private ResponseEntity<ErrorResponse> response(int status,String code,String message,HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(),status,code,message,request.getRequestURI()));
    }
}
