package com.tariffs.api;

import com.tariffs.api.TariffModels.*;
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

/** Maps domain and infrastructure errors to safe v1 responses without exposing SQL, credentials or stack traces. */
@RestControllerAdvice
public class TariffErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(TariffErrorHandler.class);
    private final Clock clock;

    /** Receives the shared UTC clock used by error and reset responses. */
    public TariffErrorHandler(Clock clock) { this.clock = clock; }

    /** Preserves intentional domain status/code and immutable field validation details. */
    @ExceptionHandler(TariffApiException.class)
    public ResponseEntity<ErrorResponse> domain(TariffApiException error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), request, error.details());
    }

    /** Returns HTTP 400 with sorted field errors for invalid rule DTO values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        List<ErrorDetail> details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new ErrorDetail(field.getField(), field.getDefaultMessage()))
                .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message)).toList();
        return response(400, "VALIDATION_ERROR", "Invalid tariff request", request, details);
    }

    /** Rejects malformed JSON, unsupported field coercion, missing query parameters and invalid UUID syntax. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception error, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Invalid request format or required parameters", request, List.of());
    }

    /** Treats database failures and failed cache-reset operations as dependency failures, with safe public text. */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    public ResponseEntity<ErrorResponse> dependency(Exception error, HttpServletRequest request) {
        log.warn("Tariff dependency operation failed: {}", error.getClass().getSimpleName());
        return response(503, "DEPENDENCY_UNAVAILABLE", "Required storage is unavailable", request, List.of());
    }

    /** Preserves HTTP 404 for unknown API/resource paths instead of misclassifying them as server faults. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missingPath(NoResourceFoundException error, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request, List.of());
    }

    /** Preserves HTTP 405 for unsupported methods with a safe validation-shaped response. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMethod(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return response(405, "VALIDATION_ERROR", "HTTP method is not supported", request, List.of());
    }

    /** Preserves HTTP 415 when the client sends a body with an unsupported media type. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMedia(HttpMediaTypeNotSupportedException error, HttpServletRequest request) {
        return response(415, "VALIDATION_ERROR", "Content type is not supported", request, List.of());
    }

    /** Logs unexpected failures for diagnosis while returning a generic contract response. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected tariff API failure", error);
        return response(500, "INTERNAL_ERROR", "Internal tariff service error", request, List.of());
    }

    /** Builds a UTC, path-scoped error response; supplied safe details never include rejected values. */
    private ResponseEntity<ErrorResponse> response(int status, String code, String message,
                                                  HttpServletRequest request, List<ErrorDetail> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(), status, code, message,
                request.getRequestURI(), details));
    }
}
