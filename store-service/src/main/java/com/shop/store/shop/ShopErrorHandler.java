package com.shop.store.shop;

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
import static com.shop.store.shop.ShopModels.*;

/** Public contract failures, keeping SQL, credentials and stack traces out of catalog/cart responses. */
@RestControllerAdvice
public class ShopErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ShopErrorHandler.class);
    private final Clock clock;
    /** Receives the application UTC clock for deterministic safe errors. */
    public ShopErrorHandler(Clock clock) { this.clock=clock; }
    /** Preserves intended scope/version/quantity errors, whose transactions roll back before this response is built. */
    @ExceptionHandler(ShopException.class)
    public ResponseEntity<ErrorResponse> domain(ShopException failure,HttpServletRequest request) {
        return response(failure.status(),failure.code(),failure.getMessage(),request);
    }
    /** Reports unavailable storage or uncertain transaction outcome without exposing JDBC details. */
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<ErrorResponse> database(Exception failure,HttpServletRequest request) {
        log.warn("STORE database operation failed",failure); return response(503,"DEPENDENCY_UNAVAILABLE","Store storage unavailable",request);
    }
    /** Rejects malformed/missing body/query parameters with a contract-shaped client error. */
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception failure,HttpServletRequest request) {
        return response(400,"VALIDATION_ERROR","Invalid or missing request data",request);
    }
    /** Returns safe 404 for removed legacy bypasses and unknown resources. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missing(Exception failure,HttpServletRequest request) {
        return response(404,"NOT_FOUND","Resource not found",request);
    }
    /** Keeps unsupported HTTP methods a client error. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(Exception failure,HttpServletRequest request) {
        return response(405,"VALIDATION_ERROR","HTTP method not supported",request);
    }
    /** Rejects unsupported content types before any cart mutation. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> media(Exception failure,HttpServletRequest request) {
        return response(415,"VALIDATION_ERROR","Content type not supported",request);
    }
    /** Logs unexpected failures while returning only a generic public error. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception failure,HttpServletRequest request) {
        log.error("Unexpected STORE API failure",failure); return response(500,"INTERNAL_ERROR","Internal store error",request);
    }
    /** Creates a consistent UTC error document without reflecting supplied body values. */
    private ResponseEntity<ErrorResponse> response(int status,String code,String message,HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(),status,code,message,request.getRequestURI()));
    }
}
