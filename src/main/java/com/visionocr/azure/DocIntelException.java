package com.visionocr.azure;

/** Failure talking to Document Intelligence. retryable = worth trying again later (timeouts, throttling). */
public class DocIntelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public DocIntelException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public DocIntelException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = false;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
