package com.cellforge.shared.error;

/**
 * Temporarily refused, for example while a tenant is frozen for a move. Reported as 503 with Retry-After.
 */
public class UnavailableException extends DomainException {

    private final int retryAfterSeconds;

    public UnavailableException(String code, String message, int retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
