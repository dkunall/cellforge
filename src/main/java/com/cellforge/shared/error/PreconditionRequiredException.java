package com.cellforge.shared.error;

/**
 * A write that must name the version it edits (If-Match) arrived without one.
 */
public class PreconditionRequiredException extends DomainException {

    public PreconditionRequiredException(String code, String message) {
        super(code, message);
    }
}
