package com.cellforge.edge.metering.application;

import com.cellforge.edge.metering.domain.UsageEvent;
import java.time.Instant;
import java.util.UUID;

public interface UsageLedger {

    /** False when this event id was already recorded. */
    boolean recordOnce(UsageEvent event);

    void addToHour(UUID tenantId, Instant hour, long calls, long errors);

    long callsBetween(UUID tenantId, Instant from, Instant to);

    int forgetEventsBefore(Instant cutoff);
}
