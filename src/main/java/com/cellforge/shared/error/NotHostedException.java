package com.cellforge.shared.error;

/**
 * The request reached a cell that does not (or no longer) hold this tenant. Reported as 421 Misdirected
 * Request, which tells the edge to refresh its directory entry and try again.
 */
public class NotHostedException extends DomainException {

    public NotHostedException(String message) {
        super("tenant-not-hosted", message);
    }
}
