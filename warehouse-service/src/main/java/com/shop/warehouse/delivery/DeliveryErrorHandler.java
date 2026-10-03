package com.shop.warehouse.delivery;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.time.Clock;
import static com.shop.warehouse.delivery.DeliveryModels.*;

/** Maps known errors to the public contract, keeping credentials, SQL and internal stack traces out of responses. */
@RestControllerAdvice
public class DeliveryErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(DeliveryErrorHandler.class);
    private final Clock clock;
    /** Receives the same UTC clock used by delivery state transitions. */
    public DeliveryErrorHandler(Clock clock) { this.clock = clock; }
    /** Preserves deliberate business codes for invalid requests, absent deliveries and retry conflicts. */
    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<ErrorResponse> domain(DeliveryException failure, HttpServletRequest request) {
        return response(failure.status(), failure.code(), failure.getMessage(), request);
    }
    /** Reports both database access and transaction-start/commit failures as dependency errors with potentially uncertain outcome. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> database(Exception failure, HttpServletRequest request) {
        log.warn("Delivery database operation failed", failure);
        return response(503, "DEPENDENCY_UNAVAILABLE", "Delivery storage unavailable", request);
    }
    /** Rejects missing or unreadable HTTP request bodies without exposing parser details. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed(Exception failure, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Invalid request body", request);
    }
    /** Returns safe contract-shaped 404 for unknown endpoints. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missing(Exception failure, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request);
    }
    /** Keeps wrong HTTP methods a client error rather than an internal failure. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(Exception failure, HttpServletRequest request) {
        return response(405, "VALIDATION_ERROR", "HTTP method not supported", request);
    }
    /** Rejects unsupported body media types before publishing anything to Kafka. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> media(Exception failure, HttpServletRequest request) {
        return response(415, "VALIDATION_ERROR", "Content type not supported", request);
    }
    /** Logs unexpected programming failures while returning only a safe generic error to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception failure, HttpServletRequest request) {
        log.error("Unexpected delivery API failure", failure);
        return response(500, "INTERNAL_ERROR", "Internal delivery error", request);
    }
    /** Builds a UTC contract response without reflecting supplied payload, SQL or secrets. */
    private ResponseEntity<ErrorResponse> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(), status, code, message, request.getRequestURI()));
    }
}
