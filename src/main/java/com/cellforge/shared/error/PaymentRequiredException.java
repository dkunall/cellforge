package com.cellforge.shared.error;

/** The workspace's billing standing blocks the request. Reported as 402. */
public class PaymentRequiredException extends DomainException {

    public PaymentRequiredException(String code, String message) {
        super(code, message);
    }
}
