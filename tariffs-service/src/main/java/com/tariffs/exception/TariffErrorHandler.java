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

/**
 * Преобразует исключения сервиса в HTTP-ответы с кодом, пояснением, временем и путём запроса. Внутренние
 * подробности сбоев не возвращает клиенту.
 */
@RestControllerAdvice
public class TariffErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(TariffErrorHandler.class);
    private final Clock clock;

    /**
     * Подключает часы для времени HTTP-ответов об ошибках.
     *
     * @param clock часы для дат операций и сроков фоновых попыток
     */
    public TariffErrorHandler(Clock clock) { this.clock = clock; }

    /**
     * Возвращает статус, код и пояснение, заданные бизнес-исключением. Путь берёт из HTTP-запроса.
     *
     * @param error ошибка тарифов с заданными статусом, кодом и пояснением
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(TariffApiException.class)
    public ResponseEntity<ErrorResponse> domain(TariffApiException error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), request, error.details());
    }

    /**
     * Возвращает HTTP 400 с именами полей и причинами нарушения их ограничений. Сортирует ошибки по полю и
     * сообщению; отклонённые значения в ответ не входят.
     *
     * @param error нарушения ограничений полей входного запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        List<ErrorDetail> details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new ErrorDetail(field.getField(), field.getDefaultMessage()))
                .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message)).toList();
        return response(400, "VALIDATION_ERROR", "Invalid tariff request", request, details);
    }

    /**
     * Возвращает HTTP 400, если тело запроса нельзя прочитать или отсутствуют обязательные параметры.
     * Подробности JSON-парсера клиенту не передаёт.
     *
     * @param error исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception error, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Invalid request format or required parameters", request, List.of());
    }

    /**
     * Возвращает HTTP 503 при ошибке БД, транзакции или сброса Redis. Внутренние подробности записывает в
     * журнал.
     *
     * @param error исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    public ResponseEntity<ErrorResponse> dependency(Exception error, HttpServletRequest request) {
        log.warn("Tariff dependency operation failed: {}", error.getClass().getSimpleName());
        return response(503, "DEPENDENCY_UNAVAILABLE", "Required storage is unavailable", request, List.of());
    }

    /**
     * Возвращает HTTP 404 для пути, которому не соответствует доступный ресурс.
     *
     * @param error ошибка обращения к отсутствующему ресурсу
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missingPath(NoResourceFoundException error, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request, List.of());
    }

    /**
     * Возвращает HTTP 405, если выбранный путь не поддерживает метод запроса.
     *
     * @param error ошибка неподдерживаемого метода HTTP-запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMethod(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return response(405, "VALIDATION_ERROR", "HTTP method is not supported", request, List.of());
    }

    /**
     * Возвращает HTTP 415, если формат тела, указанный в заголовке {@code Content-Type}, не поддерживается.
     *
     * @param error ошибка неподдерживаемого формата тела запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMedia(HttpMediaTypeNotSupportedException error, HttpServletRequest request) {
        return response(415, "VALIDATION_ERROR", "Content type is not supported", request, List.of());
    }

    /**
     * Записывает неожиданную ошибку в журнал и возвращает HTTP 500. Стек вызовов и внутренние данные
     * приложения в ответ не входят.
     *
     * @param error исходная ошибка обработки запроса
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected tariff API failure", error);
        return response(500, "INTERNAL_ERROR", "Internal tariff service error", request, List.of());
    }

    /**
     * Собирает ответ об ошибке из заданных статуса, кода и пояснения; добавляет текущее время UTC и путь
     * запроса. Пояснение должно быть заранее подготовлено для клиента.
     *
     * @param status HTTP-статус ответа
     * @param code код, по которому клиент различает причину ошибки
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @param request исходный HTTP-запрос, из которого берётся путь для ответа
     * @param details имена полей и причины нарушения без отклонённых значений
     * @return HTTP-ответ с кодом, пояснением, временем ошибки и путём запроса
     */
    private ResponseEntity<ErrorResponse> response(int status, String code, String message,
                                                  HttpServletRequest request, List<ErrorDetail> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(), status, code, message,
                request.getRequestURI(), details));
    }
}
