package com.cellforge.shared.error;

/**
 * The client edited a stale copy: its If-Match version is no longer current.
 */
public class PreconditionFailedException extends DomainException {

    public PreconditionFailedException(String code, String message) {
        super(code, message);
    }
}
