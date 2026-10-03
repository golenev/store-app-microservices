package com.tariffs.api;

import java.util.List;
import com.tariffs.api.TariffModels.ErrorDetail;

/** Explicit domain/API failure; messages contain no database or connection credentials. */
public class TariffApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<ErrorDetail> details;

    /** Carries an HTTP status, contract error code and safe explanation without validation details. */
    public TariffApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    /** Carries sorted field validation details together with the public error contract. */
    public TariffApiException(int status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    /** Returns the intended HTTP status without exposing the underlying implementation. */
    public int status() { return status; }
    /** Returns the stable machine-readable code from the v1 contract. */
    public String code() { return code; }
    /** Returns immutable field-level validation failures. */
    public List<ErrorDetail> details() { return details; }
}
