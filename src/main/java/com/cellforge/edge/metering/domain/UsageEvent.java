package com.cellforge.edge.metering.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record UsageEvent(UUID eventId, UUID tenantId, Instant at, String method, int status) {

    public Instant hour() {
        return at.truncatedTo(ChronoUnit.HOURS);
    }

    public boolean failed() {
        return status >= 500;
    }
}
