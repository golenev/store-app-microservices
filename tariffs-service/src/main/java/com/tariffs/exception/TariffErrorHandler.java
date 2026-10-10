package com.tariffs.exception;

import com.tariffs.dto.ErrorDetail;
import com.tariffs.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/** Преобразует бизнес-ошибки и сбои инфраструктуры в безопасный HTTP-контракт. */
@RestControllerAdvice
public class TariffErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(TariffErrorHandler.class);
    private final Clock clock;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param clock общие UTC-часы приложения
     */
    public TariffErrorHandler(Clock clock) { this.clock = clock; }

    /**
     * Возвращает намеренные статус и код бизнес-ошибки для request; транзакция завершается до построения
     * ответа.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(TariffApiException.class)
    public ResponseEntity<ErrorResponse> domain(TariffApiException error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), request, error.details());
    }

    /**
     * Возвращает 400 с упорядоченными ошибками полей error для request; отклонённые значения не включаются.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        List<ErrorDetail> details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new ErrorDetail(field.getField(), field.getDefaultMessage()))
                .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message)).toList();
        return response(400, "VALIDATION_ERROR", "Invalid tariff request", request, details);
    }

    /**
     * Возвращает 400 для повреждённого или отсутствующего тела и обязательных параметров request без
     * подробностей парсера.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception error, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Invalid request format or required parameters", request, List.of());
    }

    /**
     * Возвращает 503 при сбое БД, транзакции или сброса кеша; внутренние подробности сохраняет только в
     * журнале.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    public ResponseEntity<ErrorResponse> dependency(Exception error, HttpServletRequest request) {
        log.warn("Tariff dependency operation failed: {}", error.getClass().getSimpleName());
        return response(503, "DEPENDENCY_UNAVAILABLE", "Required storage is unavailable", request, List.of());
    }

    /**
     * Возвращает безопасный 404 для неизвестного пути request.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missingPath(NoResourceFoundException error, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request, List.of());
    }

    /**
     * Возвращает 405 для неподдерживаемого HTTP-метода request.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMethod(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return response(405, "VALIDATION_ERROR", "HTTP method is not supported", request, List.of());
    }

    /**
     * Возвращает 415 для неподдерживаемого Content-Type до изменения состояния.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMedia(HttpMediaTypeNotSupportedException error, HttpServletRequest request) {
        return response(415, "VALIDATION_ERROR", "Content type is not supported", request, List.of());
    }

    /**
     * Записывает неожиданную ошибку в журнал и возвращает безопасный 500 без стека или внутренних данных.
     *
     * @param error исходная ошибка бизнес-операции или инфраструктуры
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @return HTTP-ответ с описанным статусом и телом
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected tariff API failure", error);
        return response(500, "INTERNAL_ERROR", "Internal tariff service error", request, List.of());
    }

    /**
     * Строит ответ с UTC-временем, статусом, code и безопасным message для пути request; входные значения и
     * секреты не отражает.
     *
     * @param status намеренный HTTP-статус
     * @param code стабильный код ошибки контракта
     * @param message безопасное пояснение без секретов
     * @param request HTTP-запрос или параметры контракта согласно типу
     * @param details подробности нарушений полей без отклонённых значений
     * @return HTTP-ответ с описанным статусом и телом
     */
    private ResponseEntity<ErrorResponse> response(int status, String code, String message,
                                                  HttpServletRequest request, List<ErrorDetail> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(), status, code, message,
                request.getRequestURI(), details));
    }
}
