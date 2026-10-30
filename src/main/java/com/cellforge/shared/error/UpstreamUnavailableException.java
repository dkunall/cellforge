package com.cellforge.shared.error;

/**
 * A dependency outside our control (another cell, the payment provider) failed. Reported as 502 so clients can tell it apart
 * from our own errors.
 */
public class UpstreamUnavailableException extends DomainException {

    public UpstreamUnavailableException(String code, String message) {
        super(code, message);
    }
}
