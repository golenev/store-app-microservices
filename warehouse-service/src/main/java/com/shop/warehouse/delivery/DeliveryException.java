package com.shop.warehouse.delivery;

/** Carries a safe public failure; dependency exceptions must not expose their internal messages over HTTP. */
public class DeliveryException extends RuntimeException {
    private final int status;
    private final String code;
    /** Constructs a safe named API or persisted pricing error without retaining external error text. */
    public DeliveryException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    /** Returns the HTTP status; Kafka consumers persist the code instead of replying over HTTP. */
    public int status() { return status; }
    /** Returns the stable machine-readable error code. */
    public String code() { return code; }
}
