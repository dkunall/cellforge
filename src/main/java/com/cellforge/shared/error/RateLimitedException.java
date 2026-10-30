package com.cellforge.shared.error;

/** Over a rate limit or quota. Reported as 429 with Retry-After. */
public class RateLimitedException extends DomainException {

    private final int retryAfterSeconds;

    public RateLimitedException(String code, String message, int retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
