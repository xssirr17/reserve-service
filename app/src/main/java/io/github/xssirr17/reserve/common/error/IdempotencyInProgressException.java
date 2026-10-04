package io.github.xssirr17.reserve.common.error;

public class IdempotencyInProgressException extends RuntimeException {

    private final int retryAfterSeconds;

    public IdempotencyInProgressException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
