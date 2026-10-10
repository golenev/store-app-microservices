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

/** Преобразует бизнес-ошибки и сбои инфраструктуры в безопасный HTTP-контракт. */
@RestControllerAdvice
public class ShopErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ShopErrorHandler.class);
    private final Clock clock;
    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param clock общие UTC-часы приложения
     */
    public ShopErrorHandler(Clock clock) { this.clock=clock; }
    /**
     * Возвращает намеренные статус и код бизнес-ошибки для request; транзакция завершается до построения
     * ответа.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(ShopException.class)
    public ResponseEntity<ErrorResponse> domain(ShopException failure,HttpServletRequest request) {
        return response(failure.status(),failure.code(),failure.getMessage(),request);
    }
    /**
     * Возвращает 503 при недоступности БД или неопределённом результате транзакции, сохраняя безопасное
     * пояснение без SQL.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<ErrorResponse> database(Exception failure,HttpServletRequest request) {
        log.warn("STORE database operation failed",failure); return response(503,"DEPENDENCY_UNAVAILABLE","Store storage unavailable",request);
    }
    /**
     * Возвращает 400 для повреждённого или отсутствующего тела и обязательных параметров request без
     * подробностей парсера.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception failure,HttpServletRequest request) {
        return response(400,"VALIDATION_ERROR","Invalid or missing request data",request);
    }
    /**
     * Возвращает безопасный 404 для неизвестного пути request.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missing(Exception failure,HttpServletRequest request) {
        return response(404,"NOT_FOUND","Resource not found",request);
    }
    /**
     * Возвращает 405 для неподдерживаемого HTTP-метода request.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(Exception failure,HttpServletRequest request) {
        return response(405,"VALIDATION_ERROR","HTTP method not supported",request);
    }
    /**
     * Возвращает 415 для неподдерживаемого Content-Type до изменения состояния или публикации.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> media(Exception failure,HttpServletRequest request) {
        return response(415,"VALIDATION_ERROR","Content type not supported",request);
    }
    /**
     * Записывает неожиданную ошибку в журнал и возвращает безопасный 500 без стека или внутренних данных.
     *
     * @param failure ошибка текущей попытки или её сериализованное описание
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception failure,HttpServletRequest request) {
        log.error("Unexpected STORE API failure",failure); return response(500,"INTERNAL_ERROR","Internal store error",request);
    }
    /**
     * Строит ответ с UTC-временем, статусом, code и безопасным message для пути request; входные значения и
     * секреты не отражает.
     *
     * @param status намеренный HTTP-статус
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    private ResponseEntity<ErrorResponse> response(int status,String code,String message,HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(),status,code,message,request.getRequestURI()));
    }
}
