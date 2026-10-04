package com.shop.store.shop;

/** Safe named business error used by HTTP or persisted Kafka diagnostics, without reflecting dependency internals. */
public class ShopException extends RuntimeException {
    private final int status;
    private final String code;
    /** Carries a stable status/code and a safe descriptive message. */
    public ShopException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    /** Returns the HTTP status; consumers persist the code instead of producing an HTTP response. */
    public int status() { return status; }
    /** Returns the machine-readable failure code. */
    public String code() { return code; }
}
